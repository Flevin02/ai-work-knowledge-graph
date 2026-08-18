package com.flevin.knowgraph.server.service.document.impl;

import com.flevin.knowgraph.common.enums.ErrorCode;
import com.flevin.knowgraph.common.exception.TipsException;
import com.flevin.knowgraph.server.model.document.DocumentImportBatchStatus;
import com.flevin.knowgraph.server.model.document.DocumentImportFileResult;
import com.flevin.knowgraph.server.model.document.DocumentImportFileStatus;
import com.flevin.knowgraph.server.model.document.DocumentImportResponse;
import com.flevin.knowgraph.server.model.document.ImportBatch;
import com.flevin.knowgraph.server.model.document.SourceDocument;
import com.flevin.knowgraph.server.model.document.SourceDocumentResponse;
import com.flevin.knowgraph.server.repository.document.ImportBatchRepository;
import com.flevin.knowgraph.server.repository.document.SourceDocumentRepository;
import com.flevin.knowgraph.server.service.document.DocumentService;
import com.flevin.knowgraph.server.service.space.KnowledgeSpaceService;
import com.flevin.knowgraph.server.storage.LocalFileStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * 来源资料服务实现，维护原始文件、解析文本、内容指纹和导入批次的一致边界。
 */
@Slf4j
@Service
public class DocumentServiceImpl implements DocumentService {

    private static final String ACTIVE_STATUS = "active";
    private static final int EXCERPT_MAX_LENGTH = 160;

    private final SourceDocumentRepository sourceDocumentRepository;
    private final ImportBatchRepository importBatchRepository;
    private final KnowledgeSpaceService knowledgeSpaceService;
    private final LocalFileStorage localFileStorage;

    public DocumentServiceImpl(
            SourceDocumentRepository sourceDocumentRepository,
            ImportBatchRepository importBatchRepository,
            KnowledgeSpaceService knowledgeSpaceService,
            LocalFileStorage localFileStorage
    ) {
        this.sourceDocumentRepository = sourceDocumentRepository;
        this.importBatchRepository = importBatchRepository;
        this.knowledgeSpaceService = knowledgeSpaceService;
        this.localFileStorage = localFileStorage;
    }

    /**
     * 导入一批 Markdown/TXT 来源资料，逐文件返回成功、重复或失败结果。
     *
     * @param spaceId 知识空间标识
     * @param files 用户上传的来源资料；为空时返回参数提示
     * @return 带批次统计和逐文件结果的导入响应
     */
    @Override
    public DocumentImportResponse importDocuments(
            String spaceId,
            List<MultipartFile> files
    ) {
        // 校验来源资料所属知识空间当前有效
        knowledgeSpaceService.requireActive(spaceId);

        if (files == null || files.isEmpty()) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "请选择需要导入的 Markdown 或 TXT 文件");
        }

        // 创建本次 multipart 请求的导入批次标识
        String batchId = UUID.randomUUID().toString();

        // 获取批次创建时间，供批次和来源记录统一使用 UTC 时间
        Instant createdAt = Instant.now();

        ImportBatch initialBatch = new ImportBatch(
                batchId,
                spaceId,
                DocumentImportBatchStatus.PROCESSING.getValue(),
                files.size(),
                0,
                0,
                0,
                createdAt,
                null
        );

        // 先保存处理中的批次，确保后续每份来源资料都有可追溯的批次外键
        importBatchRepository.save(initialBatch);

        // 逐文件完成解析、重复识别、原始文件落盘和来源记录持久化
        List<DocumentImportFileResult> results = files.stream()
                .map(file -> importFile(spaceId, batchId, file))
                .toList();

        // 汇总成功导入数量
        int importedCount = countByStatus(results, DocumentImportFileStatus.IMPORTED);

        // 汇总重复内容数量
        int duplicateCount = countByStatus(results, DocumentImportFileStatus.DUPLICATE);

        // 汇总解析或保存失败数量
        int failedCount = countByStatus(results, DocumentImportFileStatus.FAILED);

        // 根据逐文件结果确定批次是否完整成功、部分失败或全部失败
        DocumentImportBatchStatus batchStatus = resolveBatchStatus(
                files.size(),
                failedCount
        );

        // 获取批次完成时间，明确批次生命周期边界
        Instant completedAt = Instant.now();

        // 更新批次最终状态和分类统计
        importBatchRepository.complete(
                batchId,
                batchStatus.getValue(),
                importedCount,
                duplicateCount,
                failedCount,
                completedAt
        );

        return new DocumentImportResponse(
                batchId,
                batchStatus,
                files.size(),
                importedCount,
                duplicateCount,
                failedCount,
                results
        );
    }

    /**
     * 查询当前数据库中已成功持久化的来源资料摘要。
     *
     * @param spaceId 知识空间标识
     * @return 按首次导入时间倒序排列的来源资料列表
     */
    @Override
    public List<SourceDocumentResponse> listDocuments(String spaceId) {
        // 校验来源资料所属知识空间当前有效
        knowledgeSpaceService.requireActive(spaceId);

        // 查询指定空间来源资料并转换为不暴露存储路径和完整文本的接口响应
        return sourceDocumentRepository.findAll(spaceId).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 导入单份来源资料，并把可预期的文件问题转换为文件级失败结果。
     *
     * @param spaceId 当前知识空间标识
     * @param batchId 当前导入批次标识
     * @param file 当前上传文件
     * @return 单文件导入结果
     */
    private DocumentImportFileResult importFile(
            String spaceId,
            String batchId,
            MultipartFile file
    ) {
        // 规范化浏览器或 multipart 客户端提供的原始文件名
        String originalName = normalizeFileName(file.getOriginalFilename());

        try {
            // 读取原始文件字节，内容指纹必须基于未经转换的事实源
            byte[] contentBytes = file.getBytes();

            // 校验文件类型、空内容和 UTF-8 编码，并得到解析文本
            ParsedDocument parsedDocument = parseDocument(originalName, contentBytes);

            // 计算原始字节内容的 SHA-256 指纹
            String contentHash = calculateSha256(contentBytes);

            // 查询相同内容是否已经导入，避免重复落盘和重复来源记录
            Optional<SourceDocument> existingDocument = sourceDocumentRepository.findByContentHash(
                    spaceId,
                    contentHash
            );
            if (existingDocument.isPresent()) {
                return new DocumentImportFileResult(
                        originalName,
                        DocumentImportFileStatus.DUPLICATE,
                        "内容与已导入资料重复，未再次保存",
                        toResponse(existingDocument.get())
                );
            }

            // 将原始文件保存到服务端上传目录，文件名使用 UUID 避免路径注入和名称冲突
            Path storedFile = localFileStorage.storeSourceDocument(
                    spaceId,
                    parsedDocument.extension(),
                    contentBytes
            );

            // 获取来源资料的统一创建和更新时间
            Instant importedAt = Instant.now();

            SourceDocument document = new SourceDocument(
                    UUID.randomUUID().toString(),
                    spaceId,
                    batchId,
                    originalName,
                    parsedDocument.kind(),
                    contentHash,
                    storedFile.toString(),
                    parsedDocument.contentText(),
                    buildExcerpt(parsedDocument.contentText()),
                    ACTIVE_STATUS,
                    contentBytes.length,
                    importedAt,
                    importedAt
            );

            try {
                // 保存来源资料结构化索引和解析文本
                sourceDocumentRepository.save(document);
            } catch (RuntimeException exception) {
                // 数据库保存失败时清理本次新落盘文件，避免形成无来源记录的孤儿文件
                localFileStorage.deleteOrphanFile(storedFile);
                throw exception;
            }

            return new DocumentImportFileResult(
                    originalName,
                    DocumentImportFileStatus.IMPORTED,
                    "来源资料已导入",
                    toResponse(document)
            );
        } catch (DocumentParseException exception) {
            log.warn("来源资料导入失败: file={}, reason={}", originalName, exception.getMessage());
            return new DocumentImportFileResult(
                    originalName,
                    DocumentImportFileStatus.FAILED,
                    exception.getMessage(),
                    null
            );
        } catch (IOException exception) {
            log.warn("来源资料读取或保存失败: file={}", originalName, exception);
            return new DocumentImportFileResult(
                    originalName,
                    DocumentImportFileStatus.FAILED,
                    "文件读取或保存失败，请检查服务端存储目录",
                    null
            );
        }
    }

    /**
     * 校验并解析 Markdown/TXT 文件内容。
     *
     * @param originalName 原始文件名
     * @param contentBytes 原始文件字节
     * @return 文件类型、扩展名和 UTF-8 文本
     * @throws DocumentParseException 文件类型、内容或编码不符合当前导入规则时抛出
     */
    private ParsedDocument parseDocument(
            String originalName,
            byte[] contentBytes
    ) throws DocumentParseException {
        // 获取小写扩展名，用于限定当前阶段只接收 Markdown/TXT
        String extension = getExtension(originalName);

        String kind = switch (extension) {
            case "md", "markdown" -> "markdown";
            case "txt" -> "txt";
            default -> throw new DocumentParseException("当前仅支持 Markdown 和 TXT 文件");
        };

        if (contentBytes.length == 0) {
            throw new DocumentParseException("文件内容为空，未创建来源资料");
        }

        try {
            // 使用严格 UTF-8 解码，拒绝用替换字符掩盖原始文本编码问题
            String contentText = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(contentBytes))
                    .toString();

            // 去除 UTF-8 BOM，避免其进入后续文本抽取和内容预览
            String normalizedContent = contentText.startsWith("\uFEFF")
                    ? contentText.substring(1)
                    : contentText;

            if (normalizedContent.isBlank()) {
                throw new DocumentParseException("文件仅包含空白内容，未创建来源资料");
            }
            return new ParsedDocument(kind, extension, normalizedContent);
        } catch (CharacterCodingException exception) {
            throw new DocumentParseException("文件不是有效的 UTF-8 文本", exception);
        }
    }

    /**
     * 计算原始文件字节的 SHA-256 内容指纹。
     *
     * @param contentBytes 原始文件字节
     * @return 64 位小写十六进制 SHA-256 指纹
     */
    private String calculateSha256(byte[] contentBytes) {
        try {
            // 获取 Java 平台必须提供的 SHA-256 摘要算法
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            // 计算摘要并转换为稳定的小写十六进制字符串
            return HexFormat.of().formatHex(digest.digest(contentBytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 Java 运行时不支持 SHA-256", exception);
        }
    }

    /**
     * 生成适合列表展示的单行文本预览。
     *
     * @param contentText 解析后的完整文本
     * @return 最多 160 个字符的文本预览
     */
    private String buildExcerpt(String contentText) {
        // 合并连续空白，避免 Markdown 换行导致列表预览断裂
        String normalizedText = contentText.replaceAll("\\s+", " ").strip();
        return normalizedText.length() <= EXCERPT_MAX_LENGTH
                ? normalizedText
                : normalizedText.substring(0, EXCERPT_MAX_LENGTH);
    }

    /**
     * 清理客户端可能携带的目录片段，只保留展示用文件名。
     *
     * @param originalName multipart 原始文件名
     * @return 安全的展示文件名
     */
    private String normalizeFileName(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            return "未命名文件";
        }

        // 统一 Windows 和 Unix 路径分隔符，避免展示客户端目录片段
        String normalizedName = originalName.replace('\\', '/');

        // 截取最后一个路径分隔符后的实际文件名
        return normalizedName.substring(normalizedName.lastIndexOf('/') + 1).strip();
    }

    /**
     * 获取文件名中的小写扩展名。
     *
     * @param fileName 已规范化文件名
     * @return 小写扩展名；无扩展名时返回空字符串
     */
    private String getExtension(String fileName) {
        int separatorIndex = fileName.lastIndexOf('.');
        if (separatorIndex < 0 || separatorIndex == fileName.length() - 1) {
            return "";
        }

        // 使用固定区域规则转换扩展名，避免土耳其语等区域设置影响结果
        return fileName.substring(separatorIndex + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 统计指定单文件状态的结果数量。
     *
     * @param results 逐文件导入结果
     * @param status 需要统计的状态
     * @return 匹配状态数量
     */
    private int countByStatus(
            List<DocumentImportFileResult> results,
            DocumentImportFileStatus status
    ) {
        // 使用流式过滤聚合批次分类统计
        return Math.toIntExact(results.stream()
                .filter(result -> result.status() == status)
                .count());
    }

    /**
     * 根据失败数量确定导入批次最终状态。
     *
     * @param totalCount 文件总数
     * @param failedCount 失败文件数
     * @return 批次最终状态
     */
    private DocumentImportBatchStatus resolveBatchStatus(
            int totalCount,
            int failedCount
    ) {
        if (failedCount == 0) {
            return DocumentImportBatchStatus.COMPLETED;
        }
        if (failedCount == totalCount) {
            return DocumentImportBatchStatus.FAILED;
        }
        return DocumentImportBatchStatus.PARTIAL_FAILED;
    }

    /**
     * 将内部来源资料模型转换为安全的接口摘要。
     *
     * @param document 内部来源资料模型
     * @return 不含存储路径和完整文本的接口响应
     */
    private SourceDocumentResponse toResponse(SourceDocument document) {
        return new SourceDocumentResponse(
                document.id(),
                document.spaceId(),
                document.name(),
                document.kind(),
                document.contentHash(),
                document.excerpt(),
                document.status(),
                document.fileSize(),
                document.importedAt(),
                document.updatedAt()
        );
    }

    /**
     * 已校验并解析的文本文件中间结果。
     *
     * @param kind 前后端使用的文件类型
     * @param extension 原始文件扩展名
     * @param contentText UTF-8 完整文本
     */
    private record ParsedDocument(
            String kind,
            String extension,
            String contentText
    ) {
    }

    /**
     * 可预期的单文件解析异常，只影响当前文件，不中断同批其他文件。
     */
    private static class DocumentParseException extends Exception {

        private DocumentParseException(String message) {
            super(message);
        }

        private DocumentParseException(
                String message,
                Throwable cause
        ) {
            super(message, cause);
        }
    }
}

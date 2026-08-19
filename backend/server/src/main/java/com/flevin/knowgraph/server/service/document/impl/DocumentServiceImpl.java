package com.flevin.knowgraph.server.service.document.impl;

import com.flevin.knowgraph.common.enums.ErrorCode;
import com.flevin.knowgraph.common.exception.TipsException;
import com.flevin.knowgraph.server.model.ai.DocumentExtractionOverview;
import com.flevin.knowgraph.server.model.document.DocumentImportBatchStatus;
import com.flevin.knowgraph.server.model.document.DocumentBatchDeleteResponse;
import com.flevin.knowgraph.server.model.document.DocumentImportFileResult;
import com.flevin.knowgraph.server.model.document.DocumentImportFileStatus;
import com.flevin.knowgraph.server.model.document.DocumentImportResponse;
import com.flevin.knowgraph.server.model.document.ImportBatch;
import com.flevin.knowgraph.server.model.document.SourceDocument;
import com.flevin.knowgraph.server.model.document.SourceDocumentContentResponse;
import com.flevin.knowgraph.server.model.document.SourceDocumentExtractionSummary;
import com.flevin.knowgraph.server.model.document.SourceDocumentPage;
import com.flevin.knowgraph.server.model.document.SourceDocumentPageResponse;
import com.flevin.knowgraph.server.model.document.SourceDocumentResponse;
import com.flevin.knowgraph.server.model.document.SourceDocumentType;
import com.flevin.knowgraph.server.repository.document.ImportBatchRepository;
import com.flevin.knowgraph.server.repository.document.SourceDocumentRepository;
import com.flevin.knowgraph.server.repository.ai.AiExtractionRunRepository;
import com.flevin.knowgraph.server.repository.graph.GraphRepository;
import com.flevin.knowgraph.server.service.document.DocumentService;
import com.flevin.knowgraph.server.service.space.KnowledgeSpaceService;
import com.flevin.knowgraph.server.storage.LocalFileStorage;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 来源资料服务实现，维护原始文件、解析文本、内容指纹和导入批次的一致边界。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentService {

    private static final String ACTIVE_STATUS = "active";
    private static final int EXCERPT_MAX_LENGTH = 160;
    private static final String PDF_PAGE_MARKER = "===== 第 %d 页 =====";

    private final SourceDocumentRepository sourceDocumentRepository;
    private final ImportBatchRepository importBatchRepository;
    private final KnowledgeSpaceService knowledgeSpaceService;
    private final LocalFileStorage localFileStorage;
    private final GraphRepository graphRepository;
    private final AiExtractionRunRepository aiExtractionRunRepository;

    /**
     * 导入一批 Markdown、TXT 或文本型 PDF 来源资料，逐文件返回成功、重复或失败结果。
     *
     * @param spaceId 知识空间标识
     * @param documentType 文档业务类型；为空时按 general 处理
     * @param files 用户上传的来源资料；为空时返回参数提示
     * @return 带批次统计和逐文件结果的导入响应
     */
    @Override
    public DocumentImportResponse importDocuments(
            String spaceId,
            String documentType,
            List<MultipartFile> files
    ) {
        // 校验来源资料所属知识空间当前有效
        knowledgeSpaceService.requireActive(spaceId);

        // 解析文档业务类型，避免把 PRD 语义错误写入文件格式字段
        SourceDocumentType resolvedDocumentType = resolveDocumentType(documentType);

        if (files == null || files.isEmpty()) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "请选择需要导入的 Markdown、TXT 或文本型 PDF 文件");
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
                .map(file -> importFile(spaceId, batchId, resolvedDocumentType, file))
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
     * @param name 按原始文件名模糊查询；为空时返回当前空间全部资料
     * @param page 页码，从 1 开始
     * @param pageSize 每页数量，最大 100
     * @return 按最近更新时间倒序排列的来源资料分页结果
     */
    @Override
    public SourceDocumentPageResponse listDocuments(
            String spaceId,
            String name,
            int page,
            int pageSize
    ) {
        // 校验来源资料所属知识空间当前有效
        knowledgeSpaceService.requireActive(spaceId);

        // 通过 MyBatis-Plus 分页插件查询当前页资料和总数
        SourceDocumentPage documentPage = sourceDocumentRepository.findPage(
                spaceId,
                name,
                page,
                pageSize
        );

        // 提取当前页文档标识，限定最近抽取摘要的批量查询范围
        List<String> documentIds = documentPage.items().stream()
                .map(SourceDocument::id)
                .toList();

        // 一次批量查询当前页全部文档的最近运行和最近成功结果
        Map<String, DocumentExtractionOverview> extractionOverviews = aiExtractionRunRepository
                .findLatestByDocuments(spaceId, documentIds)
                .stream()
                .collect(Collectors.toMap(DocumentExtractionOverview::documentId, Function.identity()));

        // 将资料摘要与最近抽取状态按文档标识组装，未开始资料返回显式 not_started
        List<SourceDocumentResponse> items = documentPage.items().stream()
                .map(document -> toResponse(document, extractionOverviews.get(document.id())))
                .toList();

        return new SourceDocumentPageResponse(
                items,
                documentPage.page(),
                documentPage.pageSize(),
                documentPage.total(),
                documentPage.totalPages()
        );
    }

    /**
     * 查询指定来源资料的解析原文，用于前端纯文本预览和后续证据定位。
     *
     * @param spaceId 知识空间标识
     * @param documentId 来源资料标识
     * @return 不暴露服务端路径的原文预览响应
     */
    @Override
    public SourceDocumentContentResponse getDocumentContent(
            String spaceId,
            String documentId
    ) {
        // 校验来源资料所属知识空间当前有效
        knowledgeSpaceService.requireActive(spaceId);

        // 查询指定知识空间内的完整来源资料，防止跨空间预览原文
        SourceDocument document = sourceDocumentRepository.findById(spaceId, documentId)
                .orElseThrow(() -> new TipsException(ErrorCode.NOT_FOUND, "来源资料不存在"));

        // 仅返回解析文本和展示元数据，不暴露服务端存储路径
        return new SourceDocumentContentResponse(
                document.id(),
                document.spaceId(),
                document.name(),
                document.kind(),
                document.documentType(),
                document.contentHash(),
                document.contentText(),
                document.importedAt(),
                document.updatedAt()
        );
    }

    /**
     * 软删除来源资料，并同步失效无剩余来源支撑的图谱节点和关系。
     *
     * @param spaceId 知识空间标识
     * @param documentId 来源资料标识
     */
    @Override
    @Transactional
    public void deleteDocument(
            String spaceId,
            String documentId
    ) {
        // 校验来源资料所属知识空间当前有效
        knowledgeSpaceService.requireActive(spaceId);

        // 使用统一时间更新来源资料、图谱节点和关系状态
        Instant updatedAt = Instant.now();

        // 软删除当前仍有效的来源资料，原始文件和历史记录继续保留
        int deletedCount = sourceDocumentRepository.softDelete(
                spaceId,
                documentId,
                updatedAt
        );
        if (deletedCount == 0) {
            throw new TipsException(ErrorCode.NOT_FOUND, "来源资料不存在或已删除");
        }

        // 移除该资料对图谱节点和关系的来源贡献
        graphRepository.invalidateBySourceDocument(spaceId, documentId, updatedAt);
    }

    /**
     * 批量软删除来源资料，并在同一事务中同步失效对应图谱来源贡献。
     *
     * @param spaceId 知识空间标识
     * @param documentIds 当前知识空间内待删除的来源资料标识
     * @return 已删除资料数量和资料标识
     */
    @Override
    @Transactional
    public DocumentBatchDeleteResponse deleteDocuments(
            String spaceId,
            List<String> documentIds
    ) {
        // 校验当前知识空间仍有效，避免向已删除空间写入批量删除结果
        knowledgeSpaceService.requireActive(spaceId);

        List<String> normalizedDocumentIds = documentIds.stream()
                .map(String::strip)
                .toList();
        if (new LinkedHashSet<>(normalizedDocumentIds).size() != normalizedDocumentIds.size()) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "批量操作中不能重复选择同一份来源资料");
        }

        // 先确认所有资料均属于当前空间，避免执行到中途才出现不存在资料导致部分删除
        List<SourceDocument> documents = normalizedDocumentIds.stream()
                .map(documentId -> sourceDocumentRepository.findById(spaceId, documentId)
                        .orElseThrow(() -> new TipsException(ErrorCode.NOT_FOUND, "来源资料不存在或已删除")))
                .toList();

        // 使用同一时间戳收口本批资料及其图谱来源失效状态
        Instant updatedAt = Instant.now();
        for (SourceDocument document : documents) {
            // 软删除当前仍有效的来源资料，原始文件和历史运行记录继续保留
            int deletedCount = sourceDocumentRepository.softDelete(
                    spaceId,
                    document.id(),
                    updatedAt
            );
            if (deletedCount != 1) {
                throw new TipsException(ErrorCode.NOT_FOUND, "来源资料不存在或已删除");
            }

            // 移除当前资料对图谱节点和关系的来源贡献
            graphRepository.invalidateBySourceDocument(spaceId, document.id(), updatedAt);
        }

        return new DocumentBatchDeleteResponse(documents.size(), normalizedDocumentIds);
    }

    /**
     * 导入单份来源资料，并把可预期的文件问题转换为文件级失败结果。
     *
     * @param spaceId 当前知识空间标识
     * @param batchId 当前导入批次标识
     * @param documentType 当前文档业务类型
     * @param file 当前上传文件
     * @return 单文件导入结果
     */
    private DocumentImportFileResult importFile(
            String spaceId,
            String batchId,
            SourceDocumentType documentType,
            MultipartFile file
    ) {
        // 规范化浏览器或 multipart 客户端提供的原始文件名
        String originalName = normalizeFileName(file.getOriginalFilename());

        try {
            // 读取原始文件字节，内容指纹必须基于未经转换的事实源
            byte[] contentBytes = file.getBytes();

            // 校验文件类型和内容，并按文本文件或 PDF 规则得到可预览文本
            ParsedDocument parsedDocument = parseDocument(originalName, contentBytes);

            // 计算原始字节内容的 SHA-256 指纹
            String contentHash = calculateSha256(contentBytes);

            // 查询相同内容是否已经导入，避免重复落盘和重复来源记录
            Optional<SourceDocument> existingDocument = sourceDocumentRepository.findByContentHash(
                    spaceId,
                    contentHash
            );
            if (existingDocument.isPresent()) {
                SourceDocument existing = existingDocument.get();
                if ("deleted".equals(existing.status())) {
                    // 恢复此前软删除的相同来源资料，不重复写入原始文件
                    sourceDocumentRepository.restore(
                            spaceId,
                            existing.id(),
                            documentType,
                            Instant.now()
                    );

                    // 查询恢复后的有效来源资料，返回稳定的接口摘要
                    SourceDocument restoredDocument = sourceDocumentRepository.findById(
                                    spaceId,
                                    existing.id()
                            )
                            .orElseThrow(() -> new IllegalStateException("来源资料恢复后无法查询"));
                    return new DocumentImportFileResult(
                            originalName,
                            DocumentImportFileStatus.IMPORTED,
                            "此前已删除的相同资料已恢复，请重新执行 AI 提取",
                            toResponse(restoredDocument)
                    );
                }
                return new DocumentImportFileResult(
                        originalName,
                        DocumentImportFileStatus.DUPLICATE,
                        "内容与已导入资料重复，未再次保存",
                        toResponse(existing)
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
                    documentType,
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
     * 校验并解析 Markdown、TXT 或文本型 PDF 文件内容。
     *
     * @param originalName 原始文件名
     * @param contentBytes 原始文件字节
     * @return 文件类型、扩展名和服务端解析文本
     * @throws DocumentParseException 文件类型、内容或编码不符合当前导入规则时抛出
     */
    private ParsedDocument parseDocument(
            String originalName,
            byte[] contentBytes
    ) throws DocumentParseException {
        // 获取小写扩展名，用于限定当前阶段接收的文件格式
        String extension = getExtension(originalName);

        String kind = switch (extension) {
            case "md", "markdown" -> "markdown";
            case "txt" -> "txt";
            case "pdf" -> "pdf";
            default -> throw new DocumentParseException("当前仅支持 Markdown、TXT 和文本型 PDF 文件");
        };

        if (contentBytes.length == 0) {
            throw new DocumentParseException("文件内容为空，未创建来源资料");
        }

        if ("pdf".equals(kind)) {
            // 使用 PDFBox 解析可复制文本，并在解析文本中保留逐页边界
            return parsePdfDocument(contentBytes);
        }

        // 对 Markdown/TXT 执行严格 UTF-8 解码
        return parseUtf8Document(kind, extension, contentBytes);
    }

    /**
     * 严格解析 Markdown/TXT 文本，拒绝用替换字符掩盖编码问题。
     *
     * @param kind 前后端使用的文件类型
     * @param extension 已校验的文件扩展名
     * @param contentBytes 原始文件字节
     * @return 文件类型、扩展名和完整 UTF-8 文本
     * @throws DocumentParseException 编码非法或内容仅包含空白时抛出
     */
    private ParsedDocument parseUtf8Document(
            String kind,
            String extension,
            byte[] contentBytes
    ) throws DocumentParseException {
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
     * 解析文本型 PDF，并为每一页增加稳定的页码边界标记。
     *
     * @param contentBytes PDF 原始文件字节
     * @return kind 为 pdf、扩展名为 pdf 的解析结果
     * @throws DocumentParseException PDF 损坏、受密码保护、零页或不含可提取文本时抛出
     */
    private ParsedDocument parsePdfDocument(byte[] contentBytes) throws DocumentParseException {
        try {
            // 从原始字节加载 PDF，避免依赖临时文件或客户端路径
            try (PDDocument document = Loader.loadPDF(contentBytes)) {
                if (document.isEncrypted()) {
                    throw new DocumentParseException("PDF 已加密或受密码保护，当前无法导入");
                }

                // 获取真实页数，零页文件没有可追溯的页码边界
                int pageCount = document.getNumberOfPages();
                if (pageCount == 0) {
                    throw new DocumentParseException("PDF 不包含任何页面，未创建来源资料");
                }

                // 逐页提取文本并写入稳定页码标记，供预览和后续证据定位
                String contentText = extractPdfText(document, pageCount);
                return new ParsedDocument("pdf", "pdf", contentText);
            }
        } catch (InvalidPasswordException exception) {
            throw new DocumentParseException("PDF 已加密或受密码保护，当前无法导入", exception);
        } catch (IOException exception) {
            throw new DocumentParseException("PDF 文件已损坏或格式无效，无法解析", exception);
        }
    }

    /**
     * 从已打开的 PDF 逐页提取文本，并保留包含空白页在内的全部页码边界。
     *
     * @param document 已打开且未加密的 PDF 文档
     * @param pageCount PDF 总页数
     * @return 带页码标记的完整文本
     * @throws IOException PDFBox 读取页面内容失败时抛出
     * @throws DocumentParseException 全部页面均无可提取文本时抛出
     */
    private String extractPdfText(
            PDDocument document,
            int pageCount
    ) throws IOException, DocumentParseException {
        // 创建文本提取器，并在循环中限定单页范围
        PDFTextStripper textStripper = new PDFTextStripper();
        StringBuilder contentText = new StringBuilder();
        boolean hasExtractableText = false;

        for (int pageNumber = 1; pageNumber <= pageCount; pageNumber++) {
            // 将本次提取范围限定为当前页，避免丢失页码归属
            textStripper.setStartPage(pageNumber);
            textStripper.setEndPage(pageNumber);

            // 读取当前页文本并移除 PDFBox 添加的首尾空白
            String pageText = textStripper.getText(document).strip();
            if (pageNumber > 1) {
                contentText.append("\n\n");
            }

            // 无论页面是否为空都写入页码标记，保持原始分页结构可反查
            contentText.append(PDF_PAGE_MARKER.formatted(pageNumber));
            if (!pageText.isBlank()) {
                hasExtractableText = true;
                contentText.append('\n').append(pageText);
            }
        }

        if (!hasExtractableText) {
            throw new DocumentParseException("PDF 不含可提取文本，可能是扫描件；当前未启用 OCR");
        }
        return contentText.toString();
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
        return toResponse(document, null);
    }

    /**
     * 将内部来源资料和可选抽取概览转换为安全的接口摘要。
     *
     * @param document 内部来源资料模型
     * @param extractionOverview 最近抽取概览；未执行过抽取时为空
     * @return 不含存储路径、完整文本和完整模型输出的接口响应
     */
    private SourceDocumentResponse toResponse(
            SourceDocument document,
            DocumentExtractionOverview extractionOverview
    ) {
        SourceDocumentExtractionSummary extractionSummary = extractionOverview == null
                ? SourceDocumentExtractionSummary.notStarted()
                : new SourceDocumentExtractionSummary(
                        extractionOverview.extractionId(),
                        extractionOverview.status(),
                        extractionOverview.startedAt(),
                        extractionOverview.completedAt(),
                        extractionOverview.errorMessage()
                );
        // 优先使用最近成功运行生成的摘要，旧版运行无摘要时回退导入原文预览
        String excerpt = extractionOverview == null || extractionOverview.latestCompletedSummary() == null
                ? document.excerpt()
                : extractionOverview.latestCompletedSummary();
        return new SourceDocumentResponse(
                document.id(),
                document.spaceId(),
                document.name(),
                document.kind(),
                document.documentType(),
                document.contentHash(),
                excerpt,
                document.status(),
                document.fileSize(),
                document.importedAt(),
                document.updatedAt(),
                extractionSummary,
                extractionOverview == null ? null : extractionOverview.latestCompletedExtractionId()
        );
    }

    /**
     * 将可选接口文本解析为来源资料业务类型。
     *
     * @param documentType 接口传入的业务类型文本
     * @return 已校验业务类型；空值返回 general
     */
    private SourceDocumentType resolveDocumentType(String documentType) {
        if (documentType == null || documentType.isBlank()) {
            return SourceDocumentType.GENERAL;
        }

        // 按稳定的小写值解析文档业务类型
        return SourceDocumentType.fromValue(documentType)
                .orElseThrow(() -> new TipsException(
                        ErrorCode.PARAM_ERROR,
                        "文档业务类型仅支持 general 或 prd"
                ));
    }

    /**
     * 已校验并解析的来源资料中间结果。
     *
     * @param kind 前后端使用的文件类型
     * @param extension 原始文件扩展名
     * @param contentText 服务端解析后的完整文本
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

package com.flevin.knowgraph.server.service.ai.rag;

import com.flevin.knowgraph.server.model.ai.rag.DocumentChunk;
import com.flevin.knowgraph.server.model.ai.rag.DocumentChunkFact;
import com.flevin.knowgraph.server.model.ai.rag.DocumentSection;
import com.flevin.knowgraph.server.model.ai.rag.DocumentSectionFact;
import com.flevin.knowgraph.server.model.ai.rag.PersistedDocumentStructure;
import com.flevin.knowgraph.server.model.document.SourceDocument;
import com.flevin.knowgraph.server.repository.document.DocumentChunkRepository;
import com.flevin.knowgraph.server.repository.document.DocumentSectionRepository;
import com.flevin.knowgraph.server.util.SnowflakeIdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 章节与分片事实持久化实现，使用内容指纹和版本边界复用重复解析结果。
 */
@Service
@RequiredArgsConstructor
public class DocumentStructurePersistenceServiceImpl implements DocumentStructurePersistenceService {

    private final DocumentSectionRepository sectionRepository;
    private final DocumentChunkRepository chunkRepository;
    private final DocumentRagVersionResolver versionResolver;

    /**
     * 校验确定性解析结果并在单一事务中幂等保存章节和分片事实。
     *
     * @param document 已确认属于当前知识空间的来源资料
     * @param sections 按来源原文顺序排列的章节
     * @param chunks 按来源原文顺序排列的章节感知分片
     * @return 已持久化且带数据库事实标识的结构快照
     */
    @Override
    @Transactional
    public PersistedDocumentStructure persist(
            SourceDocument document,
            List<DocumentSection> sections,
            List<DocumentChunk> chunks
    ) {
        // 校验调用方提供的来源资料和确定性解析集合不能为空
        Objects.requireNonNull(document, "来源资料不能为空");
        Objects.requireNonNull(sections, "章节列表不能为空");
        Objects.requireNonNull(chunks, "分片列表不能为空");
        if (sections.isEmpty() || chunks.isEmpty()) {
            throw new IllegalArgumentException("非空来源资料必须生成章节和分片事实");
        }

        // 获取当前解析规则和完整分片版本，作为本次事实隔离边界
        String parserVersion = versionResolver.parserVersion();
        // 获取包含窗口参数的完整分片版本，避免参数变化后混用旧分片
        String chunkVersion = versionResolver.chunkVersion();

        // 校验章节和分片均可逐字反查到当前来源原文
        validateStructure(document, sections, chunks);

        // 按当前解析版本写入缺失章节，并复用已经存在的事实标识
        List<DocumentSectionFact> persistedSections = persistSections(
                document,
                sections,
                parserVersion
        );

        // 按当前完整分片版本写入缺失分片，并关联到本次章节事实
        List<DocumentChunkFact> persistedChunks = persistChunks(
                document,
                chunks,
                persistedSections,
                parserVersion,
                chunkVersion
        );

        return new PersistedDocumentStructure(
                parserVersion,
                chunkVersion,
                persistedSections,
                persistedChunks
        );
    }

    /**
     * 幂等保存当前解析版本的章节事实。
     *
     * @param document 来源资料
     * @param sections 当前确定性章节
     * @param parserVersion 解析规则版本
     * @return 与输入章节顺序一致的持久化事实
     */
    private List<DocumentSectionFact> persistSections(
            SourceDocument document,
            List<DocumentSection> sections,
            String parserVersion
    ) {
        // 读取当前版本已有章节，避免重复抽取产生重复事实
        Map<SectionKey, DocumentSectionFact> existingByKey = sectionRepository.findByDocument(
                        document.spaceId(),
                        document.id(),
                        parserVersion
                ).stream()
                .collect(Collectors.toMap(
                        fact -> new SectionKey(fact.sectionId(), fact.contentHash()),
                        Function.identity(),
                        (left, right) -> {
                            throw new IllegalStateException("章节事实唯一键出现重复记录");
                        },
                        LinkedHashMap::new
                ));

        Instant createdAt = Instant.now();
        for (DocumentSection section : sections) {
            // 计算章节原文指纹，作为重复解析的稳定幂等键
            String contentHash = sha256(section.contentText());
            SectionKey key = new SectionKey(section.sectionId(), contentHash);
            DocumentSectionFact existing = existingByKey.get(key);
            if (existing != null) {
                // 校验已存在事实的非键元数据未发生静默漂移
                validateExistingSection(existing, section, parserVersion);
                continue;
            }

            DocumentSectionFact fact = new DocumentSectionFact(
                    SnowflakeIdGenerator.nextId(),
                    document.spaceId(),
                    document.id(),
                    section.sectionId(),
                    parserVersion,
                    section.title(),
                    section.level(),
                    section.sectionPath(),
                    section.ordinal(),
                    section.contentText(),
                    section.startOffset(),
                    section.endOffset(),
                    contentHash,
                    createdAt,
                    createdAt
            );

            // 使用数据库唯一键抵御并发重复解析，先写入者的事实标识保持不变
            sectionRepository.save(fact);
        }

        // 重新读取实际持久化结果，确保并发幂等场景返回数据库中的真实标识
        Map<SectionKey, DocumentSectionFact> persistedByKey = sectionRepository.findByDocument(
                        document.spaceId(),
                        document.id(),
                        parserVersion
                ).stream()
                .collect(Collectors.toMap(
                        fact -> new SectionKey(fact.sectionId(), fact.contentHash()),
                        Function.identity(),
                        (left, right) -> {
                            throw new IllegalStateException("章节事实唯一键出现重复记录");
                        }
                ));

        // 按解析输入顺序返回当前内容对应的章节事实，不混入同文档历史内容版本
        return sections.stream()
                .map(section -> requireSectionFact(
                        persistedByKey,
                        section.sectionId(),
                        sha256(section.contentText())
                ))
                .toList();
    }

    /**
     * 幂等保存当前完整版本的分片事实。
     *
     * @param document 来源资料
     * @param chunks 当前确定性分片
     * @param persistedSections 已持久化章节事实
     * @param parserVersion 解析规则版本
     * @param chunkVersion 完整分片版本
     * @return 与输入分片顺序一致的持久化事实
     */
    private List<DocumentChunkFact> persistChunks(
            SourceDocument document,
            List<DocumentChunk> chunks,
            List<DocumentSectionFact> persistedSections,
            String parserVersion,
            String chunkVersion
    ) {
        // 按文档内章节标识建立事实映射，为每个分片保存明确的章节事实关联
        Map<String, DocumentSectionFact> sectionById = persistedSections.stream()
                .collect(Collectors.toMap(
                        DocumentSectionFact::sectionId,
                        Function.identity(),
                        (left, right) -> {
                            throw new IllegalStateException("当前解析结果包含重复章节标识");
                        },
                        LinkedHashMap::new
                ));

        // 读取当前版本已有分片，避免重复抽取再次生成向量事实主键
        Map<ChunkKey, DocumentChunkFact> existingByKey = chunkRepository.findByDocument(
                        document.spaceId(),
                        document.id(),
                        chunkVersion
                ).stream()
                .collect(Collectors.toMap(
                        fact -> new ChunkKey(fact.chunkId(), fact.contentHash(), fact.chunkVersion()),
                        Function.identity(),
                        (left, right) -> {
                            throw new IllegalStateException("分片事实唯一键出现重复记录");
                        },
                        LinkedHashMap::new
                ));

        Instant createdAt = Instant.now();
        for (int index = 0; index < chunks.size(); index++) {
            DocumentChunk chunk = chunks.get(index);
            // 读取分片所属章节事实，保证分片可精确反查当前解析版本
            DocumentSectionFact section = requireSectionFact(sectionById, chunk.sectionId());
            // 计算分片原文指纹，作为向量复用和重复写入的稳定边界
            String contentHash = sha256(chunk.contentText());
            ChunkKey key = new ChunkKey(chunk.chunkId(), contentHash, chunkVersion);
            DocumentChunkFact existing = existingByKey.get(key);
            if (existing != null) {
                // 校验已存在分片事实仍关联同一章节和原文位置
                validateExistingChunk(existing, chunk, section, parserVersion, index + 1);
                continue;
            }

            DocumentChunkFact fact = new DocumentChunkFact(
                    SnowflakeIdGenerator.nextId(),
                    document.spaceId(),
                    document.id(),
                    section.id(),
                    chunk.sectionId(),
                    chunk.chunkId(),
                    parserVersion,
                    chunk.sectionPath(),
                    chunk.ordinal(),
                    index + 1,
                    chunk.contentText(),
                    chunk.startOffset(),
                    chunk.endOffset(),
                    contentHash,
                    chunkVersion,
                    createdAt,
                    createdAt
            );

            // 使用数据库唯一键抵御并发重复分片，先写入者的事实标识保持不变
            chunkRepository.save(fact);
        }

        // 重新读取实际持久化结果，确保返回数据库中的真实分片标识和全局顺序
        Map<ChunkKey, DocumentChunkFact> persistedByKey = chunkRepository.findByDocument(
                        document.spaceId(),
                        document.id(),
                        chunkVersion
                ).stream()
                .collect(Collectors.toMap(
                        fact -> new ChunkKey(fact.chunkId(), fact.contentHash(), fact.chunkVersion()),
                        Function.identity(),
                        (left, right) -> {
                            throw new IllegalStateException("分片事实唯一键出现重复记录");
                        }
                ));

        // 按分片输入顺序返回当前内容版本事实，不混入同文档历史分片
        return chunks.stream()
                .map(chunk -> requireChunkFact(
                        persistedByKey,
                        chunk.chunkId(),
                        sha256(chunk.contentText()),
                        chunkVersion
                ))
                .toList();
    }

    /**
     * 校验章节、分片和来源原文之间的确定性定位关系。
     *
     * @param document 来源资料
     * @param sections 章节列表
     * @param chunks 分片列表
     */
    private void validateStructure(
            SourceDocument document,
            List<DocumentSection> sections,
            List<DocumentChunk> chunks
    ) {
        Map<String, DocumentSection> sectionById = new LinkedHashMap<>();
        for (DocumentSection section : sections) {
            if (section == null || section.sectionId() == null || section.sectionId().isBlank()) {
                throw new IllegalArgumentException("章节及章节标识不能为空");
            }
            if (section.ordinal() <= 0 || section.level() < 0) {
                throw new IllegalArgumentException("章节顺序必须大于零且标题层级不能为负数");
            }
            // 校验章节原文和偏移与来源资料逐字一致
            validateTextSlice(
                    document.contentText(),
                    section.contentText(),
                    section.startOffset(),
                    section.endOffset(),
                    "章节 " + section.sectionId()
            );
            if (sectionById.putIfAbsent(section.sectionId(), section) != null) {
                throw new IllegalArgumentException("当前解析结果包含重复章节标识: " + section.sectionId());
            }
        }

        Map<String, DocumentChunk> chunkById = new LinkedHashMap<>();
        for (DocumentChunk chunk : chunks) {
            if (chunk == null || chunk.chunkId() == null || chunk.chunkId().isBlank()) {
                throw new IllegalArgumentException("分片及分片标识不能为空");
            }
            DocumentSection section = sectionById.get(chunk.sectionId());
            if (section == null) {
                throw new IllegalArgumentException("分片引用了不存在的章节: " + chunk.sectionId());
            }
            if (chunk.ordinal() <= 0) {
                throw new IllegalArgumentException("分片顺序必须大于零");
            }
            if (!section.sectionPath().equals(chunk.sectionPath())) {
                throw new IllegalArgumentException("分片章节路径与所属章节不一致: " + chunk.chunkId());
            }
            if (chunk.startOffset() < section.startOffset() || chunk.endOffset() > section.endOffset()) {
                throw new IllegalArgumentException("分片偏移超出所属章节范围: " + chunk.chunkId());
            }
            // 校验分片原文和偏移与来源资料逐字一致
            validateTextSlice(
                    document.contentText(),
                    chunk.contentText(),
                    chunk.startOffset(),
                    chunk.endOffset(),
                    "分片 " + chunk.chunkId()
            );
            if (chunkById.putIfAbsent(chunk.chunkId(), chunk) != null) {
                throw new IllegalArgumentException("当前解析结果包含重复分片标识: " + chunk.chunkId());
            }
        }
    }

    /**
     * 校验已持久化章节与当前解析结果一致。
     *
     * @param existing 已持久化章节事实
     * @param section 当前解析章节
     * @param parserVersion 当前解析版本
     */
    private void validateExistingSection(
            DocumentSectionFact existing,
            DocumentSection section,
            String parserVersion
    ) {
        if (!existing.parserVersion().equals(parserVersion)
                || !existing.title().equals(section.title())
                || existing.level() != section.level()
                || !existing.sectionPath().equals(section.sectionPath())
                || existing.ordinal() != section.ordinal()
                || existing.startOffset() != section.startOffset()
                || existing.endOffset() != section.endOffset()
                || !existing.contentText().equals(section.contentText())) {
            throw new IllegalStateException("已存在章节事实与当前解析结果不一致: " + section.sectionId());
        }
    }

    /**
     * 校验已持久化分片与当前分片结果一致。
     *
     * @param existing 已持久化分片事实
     * @param chunk 当前分片
     * @param section 所属章节事实
     * @param parserVersion 当前解析版本
     * @param documentOrdinal 当前来源资料内全局顺序
     */
    private void validateExistingChunk(
            DocumentChunkFact existing,
            DocumentChunk chunk,
            DocumentSectionFact section,
            String parserVersion,
            int documentOrdinal
    ) {
        if (!existing.sectionRecordId().equals(section.id())
                || !existing.parserVersion().equals(parserVersion)
                || !existing.sectionPath().equals(chunk.sectionPath())
                || existing.ordinal() != chunk.ordinal()
                || existing.documentOrdinal() != documentOrdinal
                || existing.startOffset() != chunk.startOffset()
                || existing.endOffset() != chunk.endOffset()
                || !existing.contentText().equals(chunk.contentText())) {
            throw new IllegalStateException("已存在分片事实与当前分片结果不一致: " + chunk.chunkId());
        }
    }

    /**
     * 校验一个解析文本片段可以按偏移从来源原文逐字反查。
     *
     * @param documentText 来源资料完整原文
     * @param sliceText 待验证片段原文
     * @param startOffset 起始偏移
     * @param endOffset 结束偏移
     * @param label 错误定位标签
     */
    private void validateTextSlice(
            String documentText,
            String sliceText,
            int startOffset,
            int endOffset,
            String label
    ) {
        if (documentText == null || sliceText == null
                || startOffset < 0 || endOffset <= startOffset || endOffset > documentText.length()) {
            throw new IllegalArgumentException(label + " 的原文偏移无效");
        }
        // 按原始偏移截取文本，确保数据库事实可以逐字反向定位
        String actualText = documentText.substring(startOffset, endOffset);
        if (!actualText.equals(sliceText)) {
            throw new IllegalArgumentException(label + " 无法按偏移逐字反查来源原文");
        }
    }

    /**
     * 从章节事实映射中读取当前章节。
     *
     * @param factsByKey 章节键到事实映射
     * @param sectionId 章节标识
     * @param contentHash 章节内容指纹
     * @return 当前章节事实
     */
    private DocumentSectionFact requireSectionFact(
            Map<SectionKey, DocumentSectionFact> factsByKey,
            String sectionId,
            String contentHash
    ) {
        DocumentSectionFact fact = factsByKey.get(new SectionKey(sectionId, contentHash));
        if (fact == null) {
            throw new IllegalStateException("章节事实写入后未能重新读取: " + sectionId);
        }
        return fact;
    }

    /**
     * 从章节标识映射中读取当前章节。
     *
     * @param factsById 章节标识到事实映射
     * @param sectionId 章节标识
     * @return 当前章节事实
     */
    private DocumentSectionFact requireSectionFact(
            Map<String, DocumentSectionFact> factsById,
            String sectionId
    ) {
        DocumentSectionFact fact = factsById.get(sectionId);
        if (fact == null) {
            throw new IllegalStateException("分片所属章节事实不存在: " + sectionId);
        }
        return fact;
    }

    /**
     * 从分片事实映射中读取当前分片。
     *
     * @param factsByKey 分片键到事实映射
     * @param chunkId 分片标识
     * @param contentHash 分片内容指纹
     * @param chunkVersion 分片版本
     * @return 当前分片事实
     */
    private DocumentChunkFact requireChunkFact(
            Map<ChunkKey, DocumentChunkFact> factsByKey,
            String chunkId,
            String contentHash,
            String chunkVersion
    ) {
        DocumentChunkFact fact = factsByKey.get(new ChunkKey(chunkId, contentHash, chunkVersion));
        if (fact == null) {
            throw new IllegalStateException("分片事实写入后未能重新读取: " + chunkId);
        }
        return fact;
    }

    /**
     * 计算文本的 SHA-256 内容指纹。
     *
     * @param value 待计算文本
     * @return 64 位小写十六进制指纹
     */
    private String sha256(String value) {
        try {
            // 使用 JDK 标准 SHA-256 计算稳定内容指纹
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte current : digest) {
                result.append(Character.forDigit((current >>> 4) & 0x0f, 16));
                result.append(Character.forDigit(current & 0x0f, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 未提供 SHA-256", exception);
        }
    }

    /**
     * 章节内容幂等键。
     *
     * @param sectionId 文档内章节标识
     * @param contentHash 章节内容指纹
     */
    private record SectionKey(String sectionId, String contentHash) {
    }

    /**
     * 分片内容和策略幂等键。
     *
     * @param chunkId 文档内分片标识
     * @param contentHash 分片内容指纹
     * @param chunkVersion 完整分片版本
     */
    private record ChunkKey(String chunkId, String contentHash, String chunkVersion) {
    }
}

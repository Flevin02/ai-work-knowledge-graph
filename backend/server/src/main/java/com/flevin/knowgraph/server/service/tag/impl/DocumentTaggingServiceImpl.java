package com.flevin.knowgraph.server.service.tag.impl;

import com.flevin.knowgraph.common.enums.ErrorCode;
import com.flevin.knowgraph.common.exception.TipsException;
import com.flevin.knowgraph.server.model.ai.rag.DocumentChunk;
import com.flevin.knowgraph.server.model.ai.rag.DocumentSection;
import com.flevin.knowgraph.server.model.document.SourceDocument;
import com.flevin.knowgraph.server.model.tag.DocumentTag;
import com.flevin.knowgraph.server.model.tag.DocumentTagCandidate;
import com.flevin.knowgraph.server.model.tag.DocumentTagEvidence;
import com.flevin.knowgraph.server.model.tag.DocumentTagEvidenceCandidate;
import com.flevin.knowgraph.server.model.tag.DocumentTagSuggestion;
import com.flevin.knowgraph.server.model.tag.DocumentTagSuggestionResponse;
import com.flevin.knowgraph.server.model.tag.DocumentTaggingDocumentContext;
import com.flevin.knowgraph.server.model.tag.DocumentTaggingRequest;
import com.flevin.knowgraph.server.model.tag.DocumentTaggingResult;
import com.flevin.knowgraph.server.model.tag.DocumentTaggingRun;
import com.flevin.knowgraph.server.model.tag.DocumentTaggingRunResponse;
import com.flevin.knowgraph.server.model.tag.KnowledgeTag;
import com.flevin.knowgraph.server.repository.document.SourceDocumentRepository;
import com.flevin.knowgraph.server.repository.space.KnowledgeSpaceRepository;
import com.flevin.knowgraph.server.repository.tag.DocumentTagRepository;
import com.flevin.knowgraph.server.repository.tag.DocumentTaggingRunRepository;
import com.flevin.knowgraph.server.service.ai.rag.PrdMarkdownSectionParser;
import com.flevin.knowgraph.server.service.ai.rag.SectionAwareDocumentChunker;
import com.flevin.knowgraph.server.service.tag.DocumentTagPersistenceService;
import com.flevin.knowgraph.server.service.tag.DocumentTaggingClient;
import com.flevin.knowgraph.server.service.tag.DocumentTaggingService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 文档标签抽取固定 Pipeline 实现。
 *
 * <p>该服务依次执行章节解析、有限分片上下文组装、供应商无关标签抽取、
 * Schema/业务引用/逐字证据校验、suggested 批量物化和运行恢复。模型只输出
 * 本次运行的局部候选标识，数据库标识和审核状态始终由服务端生成。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentTaggingServiceImpl implements DocumentTaggingService {

    private static final String PROMPT_VERSION = "document-tag-v1";
    private static final String SCHEMA_VERSION = "document-tag-v1";
    private static final int MAX_INPUT_CHUNKS = 32;
    private static final int MAX_INPUT_CHARACTERS = 24_000;
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile(
            "\\s+",
            Pattern.UNICODE_CHARACTER_CLASS
    );

    private final KnowledgeSpaceRepository knowledgeSpaceRepository;
    private final SourceDocumentRepository sourceDocumentRepository;
    private final DocumentTaggingRunRepository runRepository;
    private final DocumentTagRepository documentTagRepository;
    private final DocumentTagPersistenceService persistenceService;
    private final PrdMarkdownSectionParser sectionParser;
    private final SectionAwareDocumentChunker documentChunker;
    private final ObjectProvider<DocumentTaggingClient> taggingClientProvider;
    private final Validator validator;

    /**
     * 为当前来源资料创建并同步执行一次标签运行。
     *
     * @param spaceId 知识空间标识
     * @param sourceDocumentId 当前来源资料标识
     * @return 运行状态和本次新保存的标签建议
     */
    @Override
    public DocumentTaggingRunResponse createRun(
            String spaceId,
            String sourceDocumentId
    ) {
        // 查询当前有效来源资料并冻结本次运行的内容指纹
        SourceDocument sourceDocument = requireDocument(spaceId, sourceDocumentId);
        Instant createdAt = Instant.now();
        DocumentTaggingRun processingRun = newProcessingRun(sourceDocument, createdAt);

        // 在解析和模型调用前保存 processing 运行，失败后仍可通过 GET 恢复
        runRepository.save(processingRun);

        List<DocumentSection> sections;
        try {
            // 使用确定性 Markdown 章节解析器保留章节路径和原文偏移
            sections = sectionParser.parse(sourceDocument.contentText());
        } catch (RuntimeException exception) {
            log.warn(
                    "文档标签章节解析失败: runId={}, documentId={}",
                    processingRun.id(),
                    sourceDocumentId,
                    exception
            );
            return finishRun(
                    processingRun,
                    "failed",
                    "parse_failed",
                    "文档标签章节解析失败",
                    null,
                    0,
                    0,
                    0,
                    0,
                    0
            );
        }

        List<DocumentChunk> chunks;
        int contextCharacterCount;
        try {
            // 按章节边界生成稳定分片，供证据定位和逐字反查
            chunks = documentChunker.chunk(sections);
            if (chunks.isEmpty()) {
                throw new IllegalStateException("标签抽取没有可用分片");
            }
            contextCharacterCount = chunks.stream()
                    .mapToInt(chunk -> chunk.contentText().length())
                    .sum();
            if (chunks.size() > MAX_INPUT_CHUNKS
                    || contextCharacterCount > MAX_INPUT_CHARACTERS) {
                throw new IllegalStateException("来源资料超过当前标签上下文上限");
            }
        } catch (RuntimeException exception) {
            log.warn(
                    "文档标签分片失败: runId={}, documentId={}",
                    processingRun.id(),
                    sourceDocumentId,
                    exception
            );
            return finishRun(
                    processingRun,
                    "failed",
                    "chunk_failed",
                    "来源资料无法形成当前标签上下文，请调整分片策略后重试",
                    null,
                    0,
                    0,
                    0,
                    0,
                    0
            );
        }

        // 获取当前环境显式提供的标签客户端；生产默认尚未接入真实实现
        DocumentTaggingClient taggingClient = taggingClientProvider.getIfAvailable();
        if (taggingClient == null) {
            return finishRun(
                    processingRun,
                    "failed",
                    "tag_extraction_failed",
                    "文档标签抽取服务未启用",
                    null,
                    chunks.size(),
                    contextCharacterCount,
                    0,
                    0,
                    0
            );
        }

        // 组装不包含存储路径、其他空间资料或数据库标识的安全模型请求
        DocumentTaggingRequest request = buildRequest(processingRun, sourceDocument, chunks);

        DocumentTaggingResult result;
        try {
            // 调用供应商无关标签客户端；当前自动测试使用可重复 Fake
            result = taggingClient.tag(request);
        } catch (RuntimeException exception) {
            log.warn(
                    "文档标签抽取失败: runId={}, documentId={}",
                    processingRun.id(),
                    sourceDocumentId,
                    exception
            );
            return finishRun(
                    processingRun,
                    "failed",
                    "tag_extraction_failed",
                    "文档标签抽取服务返回失败",
                    null,
                    chunks.size(),
                    contextCharacterCount,
                    0,
                    0,
                    1
            );
        }

        List<DocumentTagSuggestion> suggestions;
        try {
            // 校验 DTO 结构、局部标识、证据引用、文档归属和逐字原文
            suggestions = validateAndPrepareSuggestions(processingRun, request, result);
        } catch (TaggingOutputException exception) {
            return finishRun(
                    processingRun,
                    "failed",
                    exception.stage(),
                    exception.getMessage(),
                    result == null ? null : normalizeSummary(result.summary()),
                    chunks.size(),
                    contextCharacterCount,
                    0,
                    exception.evidenceFailureCount(),
                    1
            );
        }

        int createdSuggestionCount;
        try {
            // 在单独事务中批量物化全部标签，任一写入失败时整批回滚
            List<DocumentTag> savedTags = persistenceService.saveAiSuggestions(suggestions);
            createdSuggestionCount = (int) IntStream.range(0, suggestions.size())
                    .filter(index -> savedTags.get(index).id().equals(
                            suggestions.get(index).documentTag().id()
                    ))
                    .count();
        } catch (RuntimeException exception) {
            log.warn(
                    "文档标签建议持久化失败: runId={}, documentId={}",
                    processingRun.id(),
                    sourceDocumentId,
                    exception
            );
            return finishRun(
                    processingRun,
                    "failed",
                    "persistence_failed",
                    "文档标签建议保存失败",
                    normalizeSummary(result.summary()),
                    chunks.size(),
                    contextCharacterCount,
                    0,
                    0,
                    1
            );
        }

        // 完成运行并返回本次实际新保存的 suggested 标签候选
        return finishRun(
                processingRun,
                "completed",
                null,
                null,
                normalizeSummary(result.summary()),
                chunks.size(),
                contextCharacterCount,
                createdSuggestionCount,
                0,
                1
        );
    }

    /**
     * 恢复指定文档的一次标签运行及其新保存建议。
     *
     * @param spaceId 知识空间标识
     * @param sourceDocumentId 当前来源资料标识
     * @param runId 标签运行标识
     * @return 可重复恢复的运行状态和建议
     */
    @Override
    public DocumentTaggingRunResponse getRun(
            String spaceId,
            String sourceDocumentId,
            String runId
    ) {
        // 校验知识空间仍有效，防止跨空间恢复运行
        knowledgeSpaceRepository.findActiveById(spaceId)
                .orElseThrow(() -> new TipsException(ErrorCode.NOT_FOUND, "知识空间不存在"));

        // 使用空间、来源资料和运行标识三重边界读取运行快照
        DocumentTaggingRun run = runRepository.findById(spaceId, sourceDocumentId, runId)
                .orElseThrow(() -> new TipsException(ErrorCode.NOT_FOUND, "文档标签运行不存在"));

        // 批量组装该运行实际新保存的 suggested 标签和证据
        return toRunResponse(run);
    }

    /**
     * 恢复指定文档最近创建的一次标签运行。
     *
     * @param spaceId 知识空间标识
     * @param sourceDocumentId 当前来源资料标识
     * @return 最近一次运行状态和建议
     */
    @Override
    public DocumentTaggingRunResponse getLatestRun(
            String spaceId,
            String sourceDocumentId
    ) {
        // 校验知识空间仍有效，防止跨空间恢复运行
        knowledgeSpaceRepository.findActiveById(spaceId)
                .orElseThrow(() -> new TipsException(ErrorCode.NOT_FOUND, "知识空间不存在"));

        // 按空间和来源资料读取最近创建的标签运行
        DocumentTaggingRun run = runRepository.findLatest(spaceId, sourceDocumentId)
                .orElseThrow(() -> new TipsException(ErrorCode.NOT_FOUND, "文档标签运行不存在"));

        // 批量组装最近运行实际新保存的 suggested 标签和证据
        return toRunResponse(run);
    }

    /**
     * 创建带固定 Prompt/Schema 版本的 processing 运行。
     *
     * @param sourceDocument 当前来源资料
     * @param createdAt 创建时间
     * @return processing 运行领域模型
     */
    private DocumentTaggingRun newProcessingRun(
            SourceDocument sourceDocument,
            Instant createdAt
    ) {
        return new DocumentTaggingRun(
                UUID.randomUUID().toString(),
                sourceDocument.spaceId(),
                sourceDocument.id(),
                sourceDocument.contentHash(),
                "processing",
                null,
                null,
                null,
                0,
                0,
                0,
                0,
                PROMPT_VERSION,
                SCHEMA_VERSION,
                0,
                0,
                null,
                createdAt,
                null
        );
    }

    /**
     * 组装当前文档的安全标签抽取请求。
     *
     * @param run 当前标签运行
     * @param sourceDocument 当前来源资料
     * @param chunks 允许标签客户端引用的分片
     * @return 供应商无关标签请求
     */
    private DocumentTaggingRequest buildRequest(
            DocumentTaggingRun run,
            SourceDocument sourceDocument,
            List<DocumentChunk> chunks
    ) {
        DocumentTaggingDocumentContext document = new DocumentTaggingDocumentContext(
                sourceDocument.id(),
                sourceDocument.name(),
                sourceDocument.kind(),
                sourceDocument.documentType().getValue(),
                sourceDocument.contentHash(),
                chunks
        );
        return new DocumentTaggingRequest(
                run.id(),
                document,
                PROMPT_VERSION,
                SCHEMA_VERSION
        );
    }

    /**
     * 校验完整模型输出并转换为等待批量物化的标签建议。
     *
     * @param run 当前标签运行
     * @param request 服务端限定的当前文档和分片
     * @param result 模型结构化输出
     * @return 已通过所有校验的标签建议
     */
    private List<DocumentTagSuggestion> validateAndPrepareSuggestions(
            DocumentTaggingRun run,
            DocumentTaggingRequest request,
            DocumentTaggingResult result
    ) {
        if (result == null) {
            throw structuredOutputInvalid();
        }

        // 执行 DTO 和嵌套 Bean Validation，拦截必填、长度、数量和置信度越界
        Set<ConstraintViolation<DocumentTaggingResult>> violations = validator.validate(result);
        if (!violations.isEmpty()) {
            throw structuredOutputInvalid();
        }

        List<String> candidateIds = result.tags().stream()
                .map(DocumentTagCandidate::candidateId)
                .toList();
        if (new HashSet<>(candidateIds).size() != candidateIds.size()) {
            throw structuredOutputInvalid();
        }

        List<String> normalizedNames = result.tags().stream()
                .map(DocumentTagCandidate::name)
                .map(this::normalizeTagName)
                .toList();
        if (new HashSet<>(normalizedNames).size() != normalizedNames.size()) {
            throw new TaggingOutputException(
                    "structured_output_invalid",
                    "文档标签输出包含重复的规范化标签名称",
                    0
            );
        }

        List<String> evidenceIds = result.evidences().stream()
                .map(DocumentTagEvidenceCandidate::evidenceId)
                .toList();
        if (new HashSet<>(evidenceIds).size() != evidenceIds.size()) {
            throw structuredOutputInvalid();
        }

        boolean containsDuplicateEvidenceReference = result.tags().stream()
                .map(DocumentTagCandidate::evidenceIds)
                .anyMatch(ids -> new HashSet<>(ids).size() != ids.size());
        if (containsDuplicateEvidenceReference) {
            throw structuredOutputInvalid();
        }

        Set<String> referencedEvidenceIds = result.tags().stream()
                .map(DocumentTagCandidate::evidenceIds)
                .flatMap(List::stream)
                .collect(Collectors.toSet());
        if (!referencedEvidenceIds.equals(new HashSet<>(evidenceIds))) {
            throw new TaggingOutputException(
                    "structured_output_invalid",
                    "文档标签证据声明与候选引用不一致",
                    0
            );
        }

        Map<String, DocumentTagEvidenceCandidate> evidenceById = result.evidences().stream()
                .collect(Collectors.toMap(
                        DocumentTagEvidenceCandidate::evidenceId,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));

        // 每条证据只能引用当前来源资料和本次提供的真实分片
        result.evidences().forEach(evidence -> validateEvidence(request.document(), evidence));

        // 将模型局部标识转换为服务端生成的标签、关系和证据标识
        return result.tags().stream()
                .map(candidate -> toSuggestion(run, request.document(), candidate, evidenceById))
                .toList();
    }

    /**
     * 校验证据属于当前来源资料和本次安全分片，并逐字反查 quote。
     *
     * @param document 服务端提供的当前文档上下文
     * @param evidence 模型证据候选
     */
    private void validateEvidence(
            DocumentTaggingDocumentContext document,
            DocumentTagEvidenceCandidate evidence
    ) {
        if (!document.documentId().equals(evidence.sourceDocumentId())) {
            throw new TaggingOutputException(
                    "evidence_invalid",
                    "文档标签证据不属于当前来源资料",
                    1
            );
        }

        DocumentChunk chunk = document.chunks().stream()
                .filter(item -> item.chunkId().equals(evidence.chunkId()))
                .findFirst()
                .orElseThrow(() -> new TaggingOutputException(
                        "evidence_invalid",
                        "文档标签证据分片不属于本次标签上下文",
                        1
                ));
        if (!chunk.sectionPath().equals(evidence.sectionPath())
                || !chunk.contentText().contains(evidence.quote())) {
            throw new TaggingOutputException(
                    "evidence_invalid",
                    "文档标签证据无法在指定分片逐字反查",
                    1
            );
        }
    }

    /**
     * 将一条标签候选转换为服务端标识和绝对证据偏移。
     *
     * @param run 当前标签运行
     * @param document 当前文档上下文
     * @param candidate 模型标签候选
     * @param evidenceById 模型局部证据索引
     * @return 等待原子物化的标签建议
     */
    private DocumentTagSuggestion toSuggestion(
            DocumentTaggingRun run,
            DocumentTaggingDocumentContext document,
            DocumentTagCandidate candidate,
            Map<String, DocumentTagEvidenceCandidate> evidenceById
    ) {
        Instant createdAt = Instant.now();
        String tagId = UUID.randomUUID().toString();
        String documentTagId = UUID.randomUUID().toString();
        KnowledgeTag tag = new KnowledgeTag(
                tagId,
                run.spaceId(),
                candidate.name(),
                null,
                "active",
                createdAt,
                createdAt
        );
        DocumentTag documentTag = new DocumentTag(
                documentTagId,
                run.spaceId(),
                run.sourceDocumentId(),
                tagId,
                "ai",
                "suggested",
                candidate.confidence(),
                run.id(),
                run.sourceContentHash(),
                run.promptVersion(),
                run.schemaVersion(),
                null,
                createdAt,
                createdAt
        );

        List<DocumentTagEvidence> evidences = candidate.evidenceIds().stream()
                .map(evidenceById::get)
                .map(evidence -> toEvidence(
                        run.spaceId(),
                        documentTagId,
                        document,
                        evidence,
                        createdAt
                ))
                .toList();
        return new DocumentTagSuggestion(tag, documentTag, evidences);
    }

    /**
     * 将模型局部证据转换为带绝对偏移的持久化证据。
     *
     * @param spaceId 知识空间标识
     * @param documentTagId 文档标签关系标识
     * @param document 当前文档上下文
     * @param evidence 模型证据候选
     * @param createdAt 创建时间
     * @return 服务端生成标识的逐字证据
     */
    private DocumentTagEvidence toEvidence(
            String spaceId,
            String documentTagId,
            DocumentTaggingDocumentContext document,
            DocumentTagEvidenceCandidate evidence,
            Instant createdAt
    ) {
        DocumentChunk chunk = document.chunks().stream()
                .filter(item -> item.chunkId().equals(evidence.chunkId()))
                .findFirst()
                .orElseThrow();
        int relativeOffset = chunk.contentText().indexOf(evidence.quote());
        int startOffset = chunk.startOffset() + relativeOffset;
        return new DocumentTagEvidence(
                UUID.randomUUID().toString(),
                spaceId,
                documentTagId,
                document.documentId(),
                evidence.chunkId(),
                evidence.sectionPath(),
                evidence.quote(),
                startOffset,
                startOffset + evidence.quote().length(),
                createdAt
        );
    }

    /**
     * 结束 processing 运行并组装可恢复响应。
     *
     * @param processingRun 原始运行快照
     * @param status 最终状态
     * @param failureStage 失败阶段
     * @param errorMessage 脱敏后的失败摘要
     * @param summary 通过结构校验的摘要
     * @param chunkCount 输入分片数量
     * @param contextCharacterCount 输入分片字符总数
     * @param suggestionCount 本次新保存建议数量
     * @param evidenceFailureCount 证据失败候选数量
     * @param modelRequestCount 模型请求次数
     * @return 最终运行响应
     */
    private DocumentTaggingRunResponse finishRun(
            DocumentTaggingRun processingRun,
            String status,
            String failureStage,
            String errorMessage,
            String summary,
            int chunkCount,
            int contextCharacterCount,
            int suggestionCount,
            int evidenceFailureCount,
            int modelRequestCount
    ) {
        Instant completedAt = Instant.now();
        DocumentTaggingRun finalRun = new DocumentTaggingRun(
                processingRun.id(),
                processingRun.spaceId(),
                processingRun.sourceDocumentId(),
                processingRun.sourceContentHash(),
                status,
                failureStage,
                errorMessage,
                summary,
                chunkCount,
                contextCharacterCount,
                suggestionCount,
                evidenceFailureCount,
                processingRun.promptVersion(),
                processingRun.schemaVersion(),
                modelRequestCount,
                0,
                Duration.between(processingRun.createdAt(), completedAt).toMillis(),
                processingRun.createdAt(),
                completedAt
        );

        // 原子结束 processing 运行，保留创建时文档指纹和版本快照
        int updatedRows = runRepository.update(finalRun);
        if (updatedRows != 1) {
            throw new TipsException(ErrorCode.BUSINESS_ERROR, "文档标签运行状态更新失败");
        }

        // 返回可通过 GET 端点重复恢复的同一响应结构
        return toRunResponse(finalRun);
    }

    /**
     * 批量组装运行和本次新保存的标签、字典及证据响应。
     *
     * @param run 标签运行
     * @return 标签运行 API 响应
     */
    private DocumentTaggingRunResponse toRunResponse(DocumentTaggingRun run) {
        // 查询该运行实际新保存的标签；幂等复用历史候选不挂到新运行
        List<DocumentTag> documentTags = documentTagRepository.findAllByExtractionRun(
                run.spaceId(),
                run.id()
        );
        List<String> tagIds = documentTags.stream().map(DocumentTag::tagId).distinct().toList();
        List<String> documentTagIds = documentTags.stream().map(DocumentTag::id).toList();

        // 批量读取标签定义并建立标识索引，避免逐候选查询数据库
        Map<String, KnowledgeTag> tagById = documentTagRepository.findTagsByIds(
                        run.spaceId(),
                        tagIds
                ).stream()
                .collect(Collectors.toMap(KnowledgeTag::id, Function.identity()));

        // 批量读取全部候选证据并按文档标签关系分组
        Map<String, List<DocumentTagEvidence>> evidenceByDocumentTag = documentTagRepository
                .findEvidenceByDocumentTags(run.spaceId(), documentTagIds)
                .stream()
                .collect(Collectors.groupingBy(
                        DocumentTagEvidence::documentTagId,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        List<DocumentTagSuggestionResponse> responses = documentTags.stream()
                .map(documentTag -> toSuggestionResponse(
                        documentTag,
                        tagById.get(documentTag.tagId()),
                        evidenceByDocumentTag.getOrDefault(documentTag.id(), List.of())
                ))
                .toList();
        return new DocumentTaggingRunResponse(
                run.id(),
                run.sourceDocumentId(),
                run.status(),
                run.failureStage(),
                run.errorMessage(),
                run.summary(),
                run.chunkCount(),
                run.contextCharacterCount(),
                run.suggestionCount(),
                run.evidenceFailureCount(),
                run.promptVersion(),
                run.schemaVersion(),
                responses,
                run.createdAt(),
                run.completedAt()
        );
    }

    /**
     * 转换一条标签关系、标签定义和证据为 API 建议响应。
     *
     * @param documentTag 文档标签关系
     * @param tag 标签定义
     * @param evidences 已校验逐字证据
     * @return 标签建议响应
     */
    private DocumentTagSuggestionResponse toSuggestionResponse(
            DocumentTag documentTag,
            KnowledgeTag tag,
            List<DocumentTagEvidence> evidences
    ) {
        if (tag == null) {
            throw new TipsException(ErrorCode.BUSINESS_ERROR, "文档标签字典数据不完整");
        }
        List<DocumentTagSuggestionResponse.Evidence> evidenceResponses = evidences.stream()
                .map(evidence -> new DocumentTagSuggestionResponse.Evidence(
                        evidence.sourceDocumentId(),
                        evidence.chunkId(),
                        evidence.sectionPath(),
                        evidence.quote(),
                        evidence.startOffset(),
                        evidence.endOffset()
                ))
                .toList();
        return new DocumentTagSuggestionResponse(
                documentTag.id(),
                documentTag.tagId(),
                tag.name(),
                documentTag.status(),
                documentTag.confidence(),
                evidenceResponses,
                documentTag.createdAt()
        );
    }

    /**
     * 查询当前空间内的有效来源资料。
     *
     * @param spaceId 知识空间标识
     * @param sourceDocumentId 来源资料标识
     * @return 当前有效来源资料
     */
    private SourceDocument requireDocument(
            String spaceId,
            String sourceDocumentId
    ) {
        // 校验知识空间有效，避免已删除空间创建标签运行
        knowledgeSpaceRepository.findActiveById(spaceId)
                .orElseThrow(() -> new TipsException(ErrorCode.NOT_FOUND, "知识空间不存在"));

        // 使用空间和资料标识双重边界读取来源资料
        return sourceDocumentRepository.findById(spaceId, sourceDocumentId)
                .orElseThrow(() -> new TipsException(ErrorCode.NOT_FOUND, "来源资料不存在或已删除"));
    }

    /**
     * 规范化标签名称用于同次模型输出内去重。
     *
     * @param name 模型标签名称
     * @return 折叠连续空格并转小写的规范化名称
     */
    private String normalizeTagName(String name) {
        return WHITESPACE_PATTERN.matcher(name.strip()).replaceAll(" ").toLowerCase(Locale.ROOT);
    }

    /**
     * 规范化通过结构校验的文档摘要。
     *
     * @param summary 模型摘要
     * @return 去除首尾空格后的摘要；空时返回 null
     */
    private String normalizeSummary(String summary) {
        return summary == null || summary.isBlank() ? null : summary.strip();
    }

    /**
     * 创建统一的 document-tag-v1 结构错误。
     *
     * @return 带稳定失败阶段的内部异常
     */
    private TaggingOutputException structuredOutputInvalid() {
        return new TaggingOutputException(
                "structured_output_invalid",
                "文档标签结果不符合 document-tag-v1 结构",
                0
        );
    }

    /**
     * 模型结构、引用或逐字证据未通过服务端校验。
     */
    private static final class TaggingOutputException extends RuntimeException {

        private final String stage;
        private final int evidenceFailureCount;

        private TaggingOutputException(
                String stage,
                String message,
                int evidenceFailureCount
        ) {
            super(message);
            this.stage = stage;
            this.evidenceFailureCount = evidenceFailureCount;
        }

        private String stage() {
            return stage;
        }

        private int evidenceFailureCount() {
            return evidenceFailureCount;
        }
    }
}

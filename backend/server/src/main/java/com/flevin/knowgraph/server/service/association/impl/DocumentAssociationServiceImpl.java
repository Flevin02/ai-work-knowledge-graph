package com.flevin.knowgraph.server.service.association.impl;

import com.flevin.knowgraph.common.enums.ErrorCode;
import com.flevin.knowgraph.common.exception.TipsException;
import com.flevin.knowgraph.server.model.ai.DocumentExtractionOverview;
import com.flevin.knowgraph.server.model.ai.rag.DocumentChunk;
import com.flevin.knowgraph.server.model.association.DocumentAssociationCandidateContext;
import com.flevin.knowgraph.server.model.association.DocumentAssociationDecision;
import com.flevin.knowgraph.server.model.association.DocumentAssociationDocumentContext;
import com.flevin.knowgraph.server.model.association.DocumentAssociationEvidenceCandidate;
import com.flevin.knowgraph.server.model.association.DocumentAssociationRequest;
import com.flevin.knowgraph.server.model.association.DocumentAssociationResult;
import com.flevin.knowgraph.server.model.association.DocumentAssociationRun;
import com.flevin.knowgraph.server.model.association.DocumentAssociationRunResponse;
import com.flevin.knowgraph.server.model.association.DocumentCandidate;
import com.flevin.knowgraph.server.model.association.DocumentCandidateRecall;
import com.flevin.knowgraph.server.model.association.DocumentRelation;
import com.flevin.knowgraph.server.model.association.DocumentRelationEvidence;
import com.flevin.knowgraph.server.model.association.DocumentRelationResponse;
import com.flevin.knowgraph.server.model.association.DocumentRelationReviewBatchRequest;
import com.flevin.knowgraph.server.model.association.DocumentRelationReviewBatchResponse;
import com.flevin.knowgraph.server.model.document.SourceDocument;
import com.flevin.knowgraph.server.repository.ai.AiExtractionRunRepository;
import com.flevin.knowgraph.server.repository.document.SourceDocumentRepository;
import com.flevin.knowgraph.server.service.ai.rag.PrdMarkdownSectionParser;
import com.flevin.knowgraph.server.service.ai.rag.SectionAwareDocumentChunker;
import com.flevin.knowgraph.server.service.association.DocumentAssociationClient;
import com.flevin.knowgraph.server.service.association.DocumentAssociationPersistenceService;
import com.flevin.knowgraph.server.service.association.DocumentAssociationResponseMapper;
import com.flevin.knowgraph.server.service.association.DocumentAssociationService;
import com.flevin.knowgraph.server.service.association.DocumentCandidateRecallService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 文档内容关联固定 Pipeline 实现。
 *
 * <p>该服务依次执行无 Embedding 候选召回、章节感知上下文组装、模型判断、
 * 候选集合和逐字证据校验、关系建议持久化以及人工审核。模型输出只形成
 * suggested 候选，不能直接成为 confirmed 事实。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentAssociationServiceImpl implements DocumentAssociationService {

    private static final String PROMPT_VERSION = "document-association-v1";
    private static final String SCHEMA_VERSION = "document-association-v1";
    private static final String RECALL_POLICY_VERSION = "document-candidate-recall-v1";
    private static final String ASSOCIATION_POLICY_VERSION = "document-association-policy-v1";
    private static final int TOP_K = 8;
    private static final int MAX_CURRENT_CHUNKS = 8;
    private static final int MAX_CANDIDATE_CHUNKS = 3;
    private static final String LOCAL_OPERATOR = "local-user";

    private static final Set<String> RELATION_TYPES = Set.of(
            "related_to",
            "references",
            "supports",
            "updates",
            "conflicts_with",
            "none"
    );
    private static final Set<String> SYMMETRIC_RELATION_TYPES = Set.of(
            "related_to",
            "conflicts_with"
    );
    private static final Set<String> DIRECTED_RELATION_TYPES = Set.of(
            "references",
            "supports",
            "updates"
    );

    private final DocumentCandidateRecallService candidateRecallService;
    private final DocumentAssociationPersistenceService persistenceService;
    private final DocumentAssociationResponseMapper responseMapper;
    private final SourceDocumentRepository sourceDocumentRepository;
    private final AiExtractionRunRepository extractionRunRepository;
    private final PrdMarkdownSectionParser sectionParser;
    private final SectionAwareDocumentChunker documentChunker;
    private final ObjectProvider<DocumentAssociationClient> associationClientProvider;
    private final Validator validator;

    /**
     * 为当前来源资料创建并同步执行一次文档关联运行。
     *
     * @param spaceId 知识空间标识
     * @param sourceDocumentId 当前分析文档标识
     * @return 运行状态和通过服务端校验的新建议
     */
    @Override
    public DocumentAssociationRunResponse createRun(
            String spaceId,
            String sourceDocumentId
    ) {
        // 查询当前有效来源资料，冻结本次运行的内容指纹
        SourceDocument sourceDocument = requireDocument(spaceId, sourceDocumentId);
        Instant createdAt = Instant.now();
        DocumentAssociationRun processingRun = newProcessingRun(sourceDocument, createdAt);

        // 先保存 processing 运行，模型不可用或输出失败后仍可查询失败阶段
        persistenceService.saveRun(processingRun);

        DocumentCandidateRecall recall;
        try {
            // 执行冻结的 TopK=8 无 Embedding 候选召回
            recall = candidateRecallService.recall(spaceId, sourceDocumentId, TOP_K);
        } catch (RuntimeException exception) {
            log.warn(
                    "文档关联候选召回失败: runId={}, documentId={}",
                    processingRun.id(),
                    sourceDocumentId,
                    exception
            );
            return finishRun(
                    processingRun,
                    "failed",
                    "retrieval_failed",
                    "文档关联候选召回失败",
                    0,
                    0,
                    0,
                    0
            );
        }

        if (recall.candidates().isEmpty()) {
            // 空召回属于正常业务结果，不调用模型也不记录系统错误
            return finishRun(
                    processingRun,
                    "completed",
                    null,
                    null,
                    0,
                    0,
                    0,
                    0
            );
        }

        // 获取当前环境显式提供的关联判断客户端；本阶段生产默认没有真实实现
        DocumentAssociationClient associationClient = associationClientProvider.getIfAvailable();
        if (associationClient == null) {
            return finishRun(
                    processingRun,
                    "failed",
                    "association_model_failed",
                    "文档关联判断服务未启用",
                    recall.candidates().size(),
                    0,
                    0,
                    0
            );
        }

        DocumentAssociationRequest request;
        try {
            // 组装不包含存储路径和跨空间数据的安全分片上下文
            request = buildAssociationRequest(processingRun, sourceDocument, recall);
        } catch (RuntimeException exception) {
            log.warn(
                    "文档关联上下文组装失败: runId={}, documentId={}",
                    processingRun.id(),
                    sourceDocumentId,
                    exception
            );
            return finishRun(
                    processingRun,
                    "failed",
                    "retrieval_failed",
                    "文档关联候选上下文已经变化，请重新分析",
                    recall.candidates().size(),
                    0,
                    0,
                    0
            );
        }

        DocumentAssociationResult result;
        try {
            // 调用供应商无关的关系判断客户端；测试环境使用可重复 Fake
            result = associationClient.associate(request);
        } catch (RuntimeException exception) {
            log.warn(
                    "文档关联判断失败: runId={}, documentId={}",
                    processingRun.id(),
                    sourceDocumentId,
                    exception
            );
            return finishRun(
                    processingRun,
                    "failed",
                    "association_model_failed",
                    "文档关联判断服务返回失败",
                    recall.candidates().size(),
                    recall.candidates().size(),
                    0,
                    1
            );
        }

        try {
            // 校验 DTO 约束、候选一一对应、证据标识唯一和阶段 1 标签空集
            validateResultShape(request, result);
        } catch (AssociationOutputException exception) {
            return finishRun(
                    processingRun,
                    "failed",
                    exception.stage(),
                    exception.getMessage(),
                    recall.candidates().size(),
                    recall.candidates().size(),
                    0,
                    1
            );
        }

        Map<String, DocumentAssociationEvidenceCandidate> evidenceById = result.evidences().stream()
                .collect(Collectors.toMap(
                        DocumentAssociationEvidenceCandidate::evidenceId,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        Map<String, DocumentAssociationCandidateContext> candidateById = request.candidates().stream()
                .collect(Collectors.toMap(
                        candidate -> candidate.document().documentId(),
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));

        List<ValidatedSuggestion> suggestions = new ArrayList<>();
        int invalidDecisionCount = 0;
        String invalidStage = null;
        for (DocumentAssociationDecision decision : result.decisions()) {
            if ("none".equals(decision.relationType())) {
                continue;
            }
            try {
                // 将单个非 none 判断转换为最终方向、稳定分片偏移和证据角色
                suggestions.add(toValidatedSuggestion(
                        processingRun,
                        request.currentDocument(),
                        candidateById.get(decision.candidateDocumentId()),
                        decision,
                        evidenceById
                ));
            } catch (AssociationOutputException exception) {
                invalidDecisionCount++;
                invalidStage = mergeFailureStage(invalidStage, exception.stage());
                log.info(
                        "文档关联候选未通过服务端校验: runId={}, candidateDocumentId={}, stage={}",
                        processingRun.id(),
                        decision.candidateDocumentId(),
                        exception.stage()
                );
            }
        }

        if (suggestions.isEmpty() && invalidDecisionCount > 0) {
            // 全部非 none 建议无效时标记明确失败，且不进入审核列表
            return finishRun(
                    processingRun,
                    "failed",
                    invalidStage,
                    buildInvalidDecisionMessage(invalidDecisionCount, invalidStage),
                    recall.candidates().size(),
                    recall.candidates().size(),
                    0,
                    1
            );
        }

        int createdSuggestionCount = 0;
        try {
            for (ValidatedSuggestion suggestion : suggestions) {
                // 每条关系及其全部证据在一个事务中保存，重复关系键复用历史建议
                DocumentRelation savedRelation = persistenceService.saveSuggestion(
                        suggestion.relation(),
                        suggestion.evidences()
                );
                if (savedRelation.id().equals(suggestion.relation().id())) {
                    createdSuggestionCount++;
                }
            }
        } catch (RuntimeException exception) {
            log.warn(
                    "文档关联建议持久化失败: runId={}, documentId={}",
                    processingRun.id(),
                    sourceDocumentId,
                    exception
            );
            return finishRun(
                    processingRun,
                    "failed",
                    "persistence_failed",
                    "文档关联建议保存失败",
                    recall.candidates().size(),
                    recall.candidates().size(),
                    createdSuggestionCount,
                    1
            );
        }

        String warning = invalidDecisionCount == 0
                ? null
                : buildInvalidDecisionMessage(invalidDecisionCount, invalidStage);

        // 完成运行；部分候选证据无效时只过滤该候选并保留脱敏说明
        return finishRun(
                processingRun,
                "completed",
                null,
                warning,
                recall.candidates().size(),
                recall.candidates().size(),
                createdSuggestionCount,
                1
        );
    }

    /**
     * 恢复指定文档关联运行及其新保存的建议。
     *
     * @param spaceId 知识空间标识
     * @param sourceDocumentId 当前分析文档标识
     * @param runId 文档关联运行标识
     * @return 运行状态和新保存的建议
     */
    @Override
    public DocumentAssociationRunResponse getRun(
            String spaceId,
            String sourceDocumentId,
            String runId
    ) {
        // 恢复空间和文档双重隔离的一次关联运行
        DocumentAssociationRun run = persistenceService.getRun(spaceId, sourceDocumentId, runId);

        // 批量组装该运行新保存的关系和证据
        return toRunResponse(run);
    }

    /**
     * 查询一份来源资料作为任一关系端点的全部文档关系。
     *
     * @param spaceId 知识空间标识
     * @param documentId 来源资料标识
     * @return 文档关系及已校验证据
     */
    @Override
    public List<DocumentRelationResponse> listRelations(
            String spaceId,
            String documentId
    ) {
        // 查询当前来源资料作为主体或客体的全部关系
        List<DocumentRelation> relations = persistenceService.listRelationsByDocument(spaceId, documentId);

        // 使用一次批量证据查询组装 API 响应
        return toRelationResponses(spaceId, relations);
    }

    /**
     * 批量采纳或拒绝当前来源资料相关的待审核文档关系。
     *
     * @param spaceId 知识空间标识
     * @param documentId 路径中的来源资料标识
     * @param request 服务端关系标识和审核动作
     * @return 审核统计和最新关系状态
     */
    @Override
    @Transactional
    public DocumentRelationReviewBatchResponse reviewRelations(
            String spaceId,
            String documentId,
            DocumentRelationReviewBatchRequest request
    ) {
        Set<String> relationIds = request.reviews().stream()
                .map(DocumentRelationReviewBatchRequest.Item::relationId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (relationIds.size() != request.reviews().size()) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "同一批次不能重复审核同一文档关系");
        }

        // 一次查询当前资料的全部关系，验证客户端不能跨文档提交关系标识
        Map<String, DocumentRelation> relationById = persistenceService
                .listRelationsByDocument(spaceId, documentId)
                .stream()
                .collect(Collectors.toMap(DocumentRelation::id, Function.identity()));

        int acceptedCount = 0;
        int rejectedCount = 0;
        for (DocumentRelationReviewBatchRequest.Item review : request.reviews()) {
            if (!relationById.containsKey(review.relationId())) {
                throw new TipsException(ErrorCode.NOT_FOUND, "文档关系不属于当前来源资料");
            }

            // 仅按服务端关系标识执行状态机，不信任客户端重传关系内容或证据
            persistenceService.reviewRelation(
                    spaceId,
                    review.relationId(),
                    review.action().getValue(),
                    review.reason(),
                    LOCAL_OPERATOR
            );
            if (review.action() == DocumentRelationReviewBatchRequest.Action.ACCEPT) {
                acceptedCount++;
            } else {
                rejectedCount++;
            }
        }

        // 在同一事务中恢复审核后的关系状态和已校验证据
        List<DocumentRelation> reviewedRelations = persistenceService
                .listRelationsByDocument(spaceId, documentId)
                .stream()
                .filter(relation -> relationIds.contains(relation.id()))
                .toList();
        return new DocumentRelationReviewBatchResponse(
                acceptedCount,
                rejectedCount,
                toRelationResponses(spaceId, reviewedRelations)
        );
    }

    /**
     * 创建带固定版本快照的 processing 运行。
     *
     * @param sourceDocument 当前来源资料
     * @param createdAt 运行创建时间
     * @return processing 运行领域模型
     */
    private DocumentAssociationRun newProcessingRun(
            SourceDocument sourceDocument,
            Instant createdAt
    ) {
        return new DocumentAssociationRun(
                UUID.randomUUID().toString(),
                sourceDocument.spaceId(),
                sourceDocument.id(),
                sourceDocument.contentHash(),
                "processing",
                null,
                null,
                0,
                0,
                0,
                0,
                0,
                0,
                PROMPT_VERSION,
                SCHEMA_VERSION,
                RECALL_POLICY_VERSION,
                ASSOCIATION_POLICY_VERSION,
                null,
                null,
                null,
                TOP_K,
                null,
                0,
                0,
                null,
                createdAt,
                null
        );
    }

    /**
     * 组装只包含当前空间、固定候选和可定位原文分片的模型请求。
     *
     * @param run 当前关联运行
     * @param sourceDocument 当前分析文档
     * @param recall 服务端候选召回结果
     * @return 供应商无关文档关联请求
     */
    private DocumentAssociationRequest buildAssociationRequest(
            DocumentAssociationRun run,
            SourceDocument sourceDocument,
            DocumentCandidateRecall recall
    ) {
        Set<String> sourceTerms = recall.candidates().stream()
                .map(DocumentCandidate::matchedTerms)
                .flatMap(List::stream)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        recall.candidates().forEach(candidate -> {
            sourceTerms.add(candidate.name());
            sourceTerms.add(candidate.title());
        });

        // 解析当前文档并选择与本次候选集合最相关的有限分片
        List<DocumentChunk> sourceChunks = selectChunks(
                parseChunks(sourceDocument),
                sourceTerms,
                MAX_CURRENT_CHUNKS
        );
        DocumentAssociationDocumentContext currentDocument = toDocumentContext(
                sourceDocument,
                resolveSummary(sourceDocument),
                sourceChunks
        );

        List<DocumentAssociationCandidateContext> candidates = recall.candidates().stream()
                .map(candidate -> toCandidateContext(sourceDocument.spaceId(), candidate))
                .toList();

        return new DocumentAssociationRequest(
                run.id(),
                currentDocument,
                candidates,
                PROMPT_VERSION,
                SCHEMA_VERSION,
                ASSOCIATION_POLICY_VERSION
        );
    }

    /**
     * 将召回候选转换为最多三个相关分片的安全模型上下文。
     *
     * @param spaceId 知识空间标识
     * @param candidate 召回候选
     * @return 候选模型上下文
     */
    private DocumentAssociationCandidateContext toCandidateContext(
            String spaceId,
            DocumentCandidate candidate
    ) {
        // 按服务端候选标识重新读取有效来源资料并校验内容指纹未变化
        SourceDocument document = requireDocument(spaceId, candidate.documentId());
        if (!document.contentHash().equals(candidate.contentHash())) {
            throw new TipsException(ErrorCode.BUSINESS_ERROR, "候选文档内容已经变化");
        }

        // 每份候选最多提供三个相关分片，限制后续真实模型上下文规模
        List<DocumentChunk> chunks = selectChunks(
                parseChunks(document),
                new LinkedHashSet<>(candidate.matchedTerms()),
                MAX_CANDIDATE_CHUNKS
        );
        return new DocumentAssociationCandidateContext(
                toDocumentContext(document, candidate.summary(), chunks),
                candidate.matchedChannels(),
                candidate.matchedTerms(),
                candidate.score(),
                candidate.rank()
        );
    }

    /**
     * 转换为不暴露本地存储路径的文档模型上下文。
     *
     * @param document 来源资料
     * @param summary 自然摘要或导入预览
     * @param chunks 允许模型引用的分片
     * @return 安全文档上下文
     */
    private DocumentAssociationDocumentContext toDocumentContext(
            SourceDocument document,
            String summary,
            List<DocumentChunk> chunks
    ) {
        return new DocumentAssociationDocumentContext(
                document.id(),
                document.name(),
                document.kind(),
                document.documentType().getValue(),
                document.contentHash(),
                summary,
                chunks
        );
    }

    /**
     * 使用确定性章节解析器和分片器恢复来源资料分片。
     *
     * @param document 来源资料
     * @return 按原文顺序排列的可追溯分片
     */
    private List<DocumentChunk> parseChunks(SourceDocument document) {
        // 解析章节树，保留标题路径和原文偏移
        var sections = sectionParser.parse(document.contentText());

        // 按章节边界生成稳定分片
        return documentChunker.chunk(sections);
    }

    /**
     * 按命中词覆盖和原文顺序选择有限分片。
     *
     * @param chunks 全部来源分片
     * @param matchedTerms 召回命中词
     * @param limit 分片数量上限
     * @return 先按相关性选择、再按原文顺序排列的分片
     */
    private List<DocumentChunk> selectChunks(
            List<DocumentChunk> chunks,
            Set<String> matchedTerms,
            int limit
    ) {
        if (chunks.size() <= limit) {
            return chunks;
        }
        Set<String> normalizedTerms = matchedTerms.stream()
                .filter(term -> term != null && !term.isBlank())
                .map(term -> term.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        // 优先保留覆盖更多召回词的分片，分数相同时使用原文偏移稳定排序
        return chunks.stream()
                .sorted(Comparator
                        .comparingInt((DocumentChunk chunk) -> chunkTermScore(chunk, normalizedTerms))
                        .reversed()
                        .thenComparingInt(DocumentChunk::startOffset))
                .limit(limit)
                .sorted(Comparator.comparingInt(DocumentChunk::startOffset))
                .toList();
    }

    /**
     * 计算分片覆盖的有限召回词数量。
     *
     * @param chunk 来源分片
     * @param normalizedTerms 已规范化召回词
     * @return 命中词数量
     */
    private int chunkTermScore(
            DocumentChunk chunk,
            Set<String> normalizedTerms
    ) {
        String normalizedContent = chunk.contentText().toLowerCase(Locale.ROOT);
        return (int) normalizedTerms.stream()
                .filter(normalizedContent::contains)
                .count();
    }

    /**
     * 读取最近成功自然摘要，不存在时回退导入预览。
     *
     * @param document 来源资料
     * @return 可供候选判断阅读的摘要
     */
    private String resolveSummary(SourceDocument document) {
        // 批量摘要查询接口复用单文档调用，保持摘要语义与来源资料列表一致
        return extractionRunRepository.findLatestByDocuments(
                        document.spaceId(),
                        List.of(document.id())
                ).stream()
                .map(DocumentExtractionOverview::latestCompletedSummary)
                .filter(summary -> summary != null && !summary.isBlank())
                .findFirst()
                .orElse(document.excerpt());
    }

    /**
     * 校验结构化输出和服务端候选集合完整性。
     *
     * @param request 本次服务端请求
     * @param result 模型结构化输出
     */
    private void validateResultShape(
            DocumentAssociationRequest request,
            DocumentAssociationResult result
    ) {
        if (result == null) {
            throw structuredOutputInvalid();
        }

        // 执行 DTO 和嵌套 Bean Validation，拦截空字段、长度和置信度越界
        Set<ConstraintViolation<DocumentAssociationResult>> violations = validator.validate(result);
        if (!violations.isEmpty()) {
            throw structuredOutputInvalid();
        }

        Set<String> candidateIds = request.candidates().stream()
                .map(candidate -> candidate.document().documentId())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<String> decisionCandidateIds = result.decisions().stream()
                .map(DocumentAssociationDecision::candidateDocumentId)
                .toList();
        if (new HashSet<>(decisionCandidateIds).size() != decisionCandidateIds.size()
                || !candidateIds.equals(new LinkedHashSet<>(decisionCandidateIds))) {
            throw new AssociationOutputException(
                    "structured_output_invalid",
                    "文档关联判断没有与服务端候选集合一一对应"
            );
        }

        List<String> evidenceIds = result.evidences().stream()
                .map(DocumentAssociationEvidenceCandidate::evidenceId)
                .toList();
        if (new HashSet<>(evidenceIds).size() != evidenceIds.size()) {
            throw new AssociationOutputException(
                    "structured_output_invalid",
                    "文档关联判断返回了重复证据标识"
            );
        }

        Set<String> referencedEvidenceIds = result.decisions().stream()
                .map(DocumentAssociationDecision::evidenceIds)
                .flatMap(List::stream)
                .collect(Collectors.toSet());
        if (!referencedEvidenceIds.equals(new HashSet<>(evidenceIds))) {
            throw new AssociationOutputException(
                    "structured_output_invalid",
                    "文档关联判断的证据声明与引用不一致"
            );
        }

        // 阶段 1 不允许模型通过未落地标签建立关系
        boolean containsTags = result.decisions().stream()
                .anyMatch(decision -> !decision.matchedTagIds().isEmpty());
        if (containsTags) {
            throw new AssociationOutputException(
                    "structured_output_invalid",
                    "文档关联阶段 1 不接受标签匹配结果"
            );
        }

        // 对 none 和非 none 判断统一校验关系白名单、方向和证据引用语义
        result.decisions().forEach(this::validateDecisionShape);
    }

    /**
     * 将一个非 none 判断转换为通过候选、方向和逐字证据校验的关系建议。
     *
     * @param run 当前关联运行
     * @param currentDocument 当前文档上下文
     * @param candidate 服务端候选上下文
     * @param decision 模型关系判断
     * @param evidenceById 模型证据索引
     * @return 待原子持久化的关系和证据
     */
    private ValidatedSuggestion toValidatedSuggestion(
            DocumentAssociationRun run,
            DocumentAssociationDocumentContext currentDocument,
            DocumentAssociationCandidateContext candidate,
            DocumentAssociationDecision decision,
            Map<String, DocumentAssociationEvidenceCandidate> evidenceById
    ) {
        // 校验白名单关系、none 语义和方向组合
        validateDecisionShape(decision);

        List<DocumentAssociationEvidenceCandidate> selectedEvidence = decision.evidenceIds().stream()
                .map(evidenceById::get)
                .toList();
        if (selectedEvidence.stream().anyMatch(java.util.Objects::isNull)) {
            throw new AssociationOutputException(
                    "structured_output_invalid",
                    "文档关联判断引用了不存在的证据标识"
            );
        }

        // 校验每条证据只能来自当前文档或本候选，并在指定分片逐字反查
        selectedEvidence.forEach(evidence -> validateEvidence(
                currentDocument,
                candidate.document(),
                evidence
        ));
        if (SYMMETRIC_RELATION_TYPES.contains(decision.relationType())) {
            Set<String> evidenceDocumentIds = selectedEvidence.stream()
                    .map(DocumentAssociationEvidenceCandidate::sourceDocumentId)
                    .collect(Collectors.toSet());
            if (!evidenceDocumentIds.contains(currentDocument.documentId())
                    || !evidenceDocumentIds.contains(candidate.document().documentId())) {
                throw new AssociationOutputException(
                        "evidence_invalid",
                        "对称文档关系必须同时提供双方逐字证据"
                );
            }
        }

        Instant createdAt = Instant.now();
        RelationEndpoints endpoints = resolveEndpoints(currentDocument, candidate.document(), decision.direction());
        String relationId = UUID.randomUUID().toString();
        DocumentRelation relation = new DocumentRelation(
                relationId,
                run.spaceId(),
                endpoints.source().documentId(),
                endpoints.target().documentId(),
                decision.relationType(),
                decision.direction(),
                "suggested",
                resolveGenerationMode(candidate),
                decision.confidence(),
                decision.reason().strip(),
                run.id(),
                endpoints.source().contentHash(),
                endpoints.target().contentHash(),
                ASSOCIATION_POLICY_VERSION,
                null,
                createdAt,
                createdAt
        );

        List<DocumentRelationEvidence> evidences = selectedEvidence.stream()
                .map(evidence -> toRelationEvidence(
                        run.spaceId(),
                        relation,
                        currentDocument,
                        candidate.document(),
                        evidence,
                        createdAt
                ))
                .toList();
        return new ValidatedSuggestion(relation, evidences);
    }

    /**
     * 校验单个模型判断的关系白名单、方向和 evidenceIds 语义。
     *
     * @param decision 模型关系判断
     */
    private void validateDecisionShape(DocumentAssociationDecision decision) {
        if (!RELATION_TYPES.contains(decision.relationType())) {
            throw structuredOutputInvalid();
        }
        if (new HashSet<>(decision.evidenceIds()).size() != decision.evidenceIds().size()) {
            throw structuredOutputInvalid();
        }
        if ("none".equals(decision.relationType())) {
            if (!"none".equals(decision.direction()) || !decision.evidenceIds().isEmpty()) {
                throw structuredOutputInvalid();
            }
            return;
        }
        if (decision.evidenceIds().isEmpty()) {
            throw new AssociationOutputException(
                    "evidence_invalid",
                    "非 none 文档关系必须引用逐字证据"
            );
        }
        if (SYMMETRIC_RELATION_TYPES.contains(decision.relationType())
                && !"symmetric".equals(decision.direction())) {
            throw structuredOutputInvalid();
        }
        if (DIRECTED_RELATION_TYPES.contains(decision.relationType())
                && !Set.of("current_to_candidate", "candidate_to_current").contains(decision.direction())) {
            throw structuredOutputInvalid();
        }
    }

    /**
     * 校验证据文档、分片、章节和逐字原文均属于本次模型输入。
     *
     * @param currentDocument 当前文档上下文
     * @param candidateDocument 候选文档上下文
     * @param evidence 模型证据候选
     */
    private void validateEvidence(
            DocumentAssociationDocumentContext currentDocument,
            DocumentAssociationDocumentContext candidateDocument,
            DocumentAssociationEvidenceCandidate evidence
    ) {
        DocumentAssociationDocumentContext evidenceDocument;
        if (currentDocument.documentId().equals(evidence.sourceDocumentId())) {
            evidenceDocument = currentDocument;
        } else if (candidateDocument.documentId().equals(evidence.sourceDocumentId())) {
            evidenceDocument = candidateDocument;
        } else {
            throw new AssociationOutputException(
                    "evidence_invalid",
                    "文档关联证据不属于当前文档或对应候选"
            );
        }

        DocumentChunk chunk = evidenceDocument.chunks().stream()
                .filter(item -> item.chunkId().equals(evidence.chunkId()))
                .findFirst()
                .orElseThrow(() -> new AssociationOutputException(
                        "evidence_invalid",
                        "文档关联证据分片不属于本次候选上下文"
                ));
        if (!chunk.sectionPath().equals(evidence.sectionPath())
                || !chunk.contentText().contains(evidence.quote())) {
            throw new AssociationOutputException(
                    "evidence_invalid",
                    "文档关联证据无法在指定分片逐字反查"
            );
        }
    }

    /**
     * 根据模型相对方向解析最终关系主体和客体。
     *
     * @param currentDocument 当前文档上下文
     * @param candidateDocument 候选文档上下文
     * @param direction 模型方向
     * @return 最终关系两端
     */
    private RelationEndpoints resolveEndpoints(
            DocumentAssociationDocumentContext currentDocument,
            DocumentAssociationDocumentContext candidateDocument,
            String direction
    ) {
        if ("current_to_candidate".equals(direction)) {
            return new RelationEndpoints(currentDocument, candidateDocument);
        }
        if ("candidate_to_current".equals(direction)) {
            return new RelationEndpoints(candidateDocument, currentDocument);
        }
        if (currentDocument.documentId().compareTo(candidateDocument.documentId()) <= 0) {
            return new RelationEndpoints(currentDocument, candidateDocument);
        }
        return new RelationEndpoints(candidateDocument, currentDocument);
    }

    /**
     * 将模型证据转换为带绝对偏移和最终关系角色的持久化证据。
     *
     * @param spaceId 知识空间标识
     * @param relation 最终文档关系
     * @param currentDocument 当前文档上下文
     * @param candidateDocument 候选文档上下文
     * @param evidence 模型证据候选
     * @param createdAt 创建时间
     * @return 可原子保存的文档关系证据
     */
    private DocumentRelationEvidence toRelationEvidence(
            String spaceId,
            DocumentRelation relation,
            DocumentAssociationDocumentContext currentDocument,
            DocumentAssociationDocumentContext candidateDocument,
            DocumentAssociationEvidenceCandidate evidence,
            Instant createdAt
    ) {
        DocumentAssociationDocumentContext evidenceDocument = currentDocument.documentId()
                .equals(evidence.sourceDocumentId())
                ? currentDocument
                : candidateDocument;
        DocumentChunk chunk = evidenceDocument.chunks().stream()
                .filter(item -> item.chunkId().equals(evidence.chunkId()))
                .findFirst()
                .orElseThrow();
        int relativeOffset = chunk.contentText().indexOf(evidence.quote());
        int startOffset = chunk.startOffset() + relativeOffset;
        String evidenceRole = relation.sourceDocumentId().equals(evidence.sourceDocumentId())
                ? "source"
                : "target";

        return new DocumentRelationEvidence(
                UUID.randomUUID().toString(),
                spaceId,
                relation.id(),
                evidence.sourceDocumentId(),
                evidence.chunkId(),
                evidence.sectionPath(),
                evidence.quote(),
                startOffset,
                startOffset + evidence.quote().length(),
                evidenceRole,
                createdAt
        );
    }

    /**
     * 根据候选召回通道解析可解释的关系生成方式。
     *
     * @param candidate 候选上下文
     * @return 持久化 generation_mode
     */
    private String resolveGenerationMode(DocumentAssociationCandidateContext candidate) {
        return candidate.matchedChannels().contains("explicit_reference")
                ? "explicit_reference"
                : "keyword_match";
    }

    /**
     * 结束 processing 运行并组装可恢复响应。
     *
     * @param processingRun 原始运行快照
     * @param status 最终状态
     * @param failureStage 失败阶段
     * @param errorMessage 脱敏后的错误或过滤说明
     * @param candidateCount 召回候选数量
     * @param comparedCount 实际判断候选数量
     * @param suggestionCount 本次新保存建议数量
     * @param modelRequestCount 模型请求次数
     * @return 最终运行响应
     */
    private DocumentAssociationRunResponse finishRun(
            DocumentAssociationRun processingRun,
            String status,
            String failureStage,
            String errorMessage,
            int candidateCount,
            int comparedCount,
            int suggestionCount,
            int modelRequestCount
    ) {
        Instant completedAt = Instant.now();
        DocumentAssociationRun finalRun = new DocumentAssociationRun(
                processingRun.id(),
                processingRun.spaceId(),
                processingRun.sourceDocumentId(),
                processingRun.sourceContentHash(),
                status,
                failureStage,
                errorMessage,
                candidateCount,
                comparedCount,
                suggestionCount,
                0,
                candidateCount,
                0,
                processingRun.promptVersion(),
                processingRun.schemaVersion(),
                processingRun.candidateRecallPolicyVersion(),
                processingRun.associationPolicyVersion(),
                null,
                null,
                null,
                processingRun.topK(),
                null,
                modelRequestCount,
                0,
                Duration.between(processingRun.createdAt(), completedAt).toMillis(),
                processingRun.createdAt(),
                completedAt
        );

        // 原子更新运行最终状态和统计，保留创建时版本快照
        DocumentAssociationRun savedRun = persistenceService.updateRun(finalRun);

        // 返回可通过 GET 端点重复恢复的同一响应结构
        return toRunResponse(savedRun);
    }

    /**
     * 将运行领域模型和新保存关系转换为 API 响应。
     *
     * @param run 文档关联运行
     * @return 运行响应
     */
    private DocumentAssociationRunResponse toRunResponse(DocumentAssociationRun run) {
        // 查询该运行实际新保存的关系；幂等复用历史关系不重复挂到新运行
        List<DocumentRelation> relations = persistenceService.listRelationsByRun(run.spaceId(), run.id());
        // 将运行快照和批量组装后的关系转换为 API 响应
        return responseMapper.toRunResponse(
                run,
                toRelationResponses(run.spaceId(), relations)
        );
    }

    /**
     * 批量组装关系及其证据响应。
     *
     * @param spaceId 知识空间标识
     * @param relations 文档关系列表
     * @return API 关系响应
     */
    private List<DocumentRelationResponse> toRelationResponses(
            String spaceId,
            List<DocumentRelation> relations
    ) {
        List<String> relationIds = relations.stream().map(DocumentRelation::id).toList();

        // 一次批量读取全部关系证据并按关系标识分组
        Map<String, List<DocumentRelationEvidence>> evidenceByRelation = persistenceService
                .listEvidence(spaceId, relationIds)
                .stream()
                .collect(Collectors.groupingBy(
                        DocumentRelationEvidence::documentRelationId,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        return relations.stream()
                .map(relation -> toRelationResponse(
                        relation,
                        evidenceByRelation.getOrDefault(relation.id(), List.of())
                ))
                .toList();
    }

    /**
     * 转换单条文档关系和证据为 API 响应。
     *
     * @param relation 文档关系
     * @param evidences 关系证据
     * @return API 关系响应
     */
    private DocumentRelationResponse toRelationResponse(
            DocumentRelation relation,
            List<DocumentRelationEvidence> evidences
    ) {
        List<DocumentRelationResponse.Evidence> evidenceResponses = evidences.stream()
                .map(responseMapper::toEvidenceResponse)
                .toList();
        // 将关系领域模型和已校验证据转换为 API 响应
        return responseMapper.toRelationResponse(relation, evidenceResponses);
    }

    /**
     * 查询当前空间有效来源资料。
     *
     * @param spaceId 知识空间标识
     * @param documentId 来源资料标识
     * @return 有效来源资料
     */
    private SourceDocument requireDocument(
            String spaceId,
            String documentId
    ) {
        // 使用空间和资料标识双重边界读取来源资料
        return sourceDocumentRepository.findById(spaceId, documentId)
                .orElseThrow(() -> new TipsException(ErrorCode.NOT_FOUND, "来源资料不存在或已删除"));
    }

    /**
     * 合并多个无效候选的失败阶段，结构错误优先于证据错误。
     *
     * @param currentStage 当前失败阶段
     * @param nextStage 新失败阶段
     * @return 合并后的失败阶段
     */
    private String mergeFailureStage(
            String currentStage,
            String nextStage
    ) {
        if ("structured_output_invalid".equals(currentStage)
                || "structured_output_invalid".equals(nextStage)) {
            return "structured_output_invalid";
        }
        return "evidence_invalid";
    }

    /**
     * 生成不包含模型原文的候选过滤说明。
     *
     * @param invalidDecisionCount 无效候选数量
     * @param stage 失败阶段
     * @return 脱敏后的稳定说明
     */
    private String buildInvalidDecisionMessage(
            int invalidDecisionCount,
            String stage
    ) {
        String reason = "structured_output_invalid".equals(stage)
                ? "结构或方向不合法"
                : "证据无法逐字反查";
        return invalidDecisionCount + " 个候选因" + reason + "未进入审核";
    }

    /**
     * 创建统一的结构化输出非法异常。
     *
     * @return 带稳定失败阶段和脱敏说明的异常
     */
    private AssociationOutputException structuredOutputInvalid() {
        return new AssociationOutputException(
                "structured_output_invalid",
                "文档关联判断结果不符合 document-association-v1 结构"
        );
    }

    /**
     * 已完成服务端校验、等待持久化的一条关系建议。
     *
     * @param relation 文档关系
     * @param evidences 关系证据
     */
    private record ValidatedSuggestion(
            DocumentRelation relation,
            List<DocumentRelationEvidence> evidences
    ) {
    }

    /**
     * 最终关系主体和客体。
     *
     * @param source 关系主体文档
     * @param target 关系客体文档
     */
    private record RelationEndpoints(
            DocumentAssociationDocumentContext source,
            DocumentAssociationDocumentContext target
    ) {
    }

    /**
     * 模型结构或证据未通过服务端边界校验。
     */
    private static final class AssociationOutputException extends RuntimeException {

        private final String stage;

        private AssociationOutputException(
                String stage,
                String message
        ) {
            super(message);
            this.stage = stage;
        }

        private String stage() {
            return stage;
        }
    }
}

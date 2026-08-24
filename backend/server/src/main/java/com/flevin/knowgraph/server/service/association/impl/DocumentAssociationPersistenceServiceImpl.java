package com.flevin.knowgraph.server.service.association.impl;

import com.flevin.knowgraph.common.enums.ErrorCode;
import com.flevin.knowgraph.common.exception.TipsException;
import com.flevin.knowgraph.server.model.association.DocumentAssociationRun;
import com.flevin.knowgraph.server.model.association.DocumentRelation;
import com.flevin.knowgraph.server.model.association.DocumentRelationEvidence;
import com.flevin.knowgraph.server.model.association.DocumentRelationReview;
import com.flevin.knowgraph.server.model.ai.rag.DocumentChunk;
import com.flevin.knowgraph.server.model.document.SourceDocument;
import com.flevin.knowgraph.server.repository.association.DocumentAssociationRunRepository;
import com.flevin.knowgraph.server.repository.association.DocumentRelationEvidenceRepository;
import com.flevin.knowgraph.server.repository.association.DocumentRelationRepository;
import com.flevin.knowgraph.server.repository.association.DocumentRelationReviewRepository;
import com.flevin.knowgraph.server.repository.document.SourceDocumentRepository;
import com.flevin.knowgraph.server.repository.space.KnowledgeSpaceRepository;
import com.flevin.knowgraph.server.service.association.DocumentAssociationPersistenceService;
import com.flevin.knowgraph.server.service.ai.rag.PrdMarkdownSectionParser;
import com.flevin.knowgraph.server.service.ai.rag.SectionAwareDocumentChunker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * 文档关联阶段 1 持久化服务实现，集中处理空间、文档、关系方向和证据边界。
 */
@Service
@RequiredArgsConstructor
public class DocumentAssociationPersistenceServiceImpl implements DocumentAssociationPersistenceService {

    private static final Set<String> RELATION_TYPES = Set.of(
            "related_to",
            "references",
            "supports",
            "updates",
            "conflicts_with"
    );

    private static final Set<String> GENERATION_MODES = Set.of(
            "explicit_reference",
            "tag_match",
            "keyword_match",
            "semantic_match",
            "hybrid",
            "user"
    );

    private static final Set<String> RUN_STATUSES = Set.of("processing", "completed", "failed");
    private static final Set<String> RELATION_STATUSES = Set.of("suggested", "confirmed", "rejected", "stale");
    private static final Set<String> REVIEW_ACTIONS = Set.of("accept", "reject", "create");
    private static final Set<String> EVIDENCE_ROLES = Set.of("source", "target", "cross_reference");

    private final KnowledgeSpaceRepository knowledgeSpaceRepository;
    private final SourceDocumentRepository sourceDocumentRepository;
    private final DocumentAssociationRunRepository runRepository;
    private final DocumentRelationRepository relationRepository;
    private final DocumentRelationEvidenceRepository evidenceRepository;
    private final DocumentRelationReviewRepository reviewRepository;
    private final PrdMarkdownSectionParser sectionParser;
    private final SectionAwareDocumentChunker documentChunker;

    /**
     * 保存一条文档关联运行记录，并校验主体文档和知识空间归属。
     *
     * @param run 文档关联运行领域模型
     * @return 已保存的文档关联运行
     */
    @Override
    @Transactional
    public DocumentAssociationRun saveRun(DocumentAssociationRun run) {
        // 校验运行记录字段和运行状态，避免非法快照进入数据库
        validateRun(run);

        // 校验知识空间仍然有效，保持运行记录的空间隔离
        requireActiveSpace(run.spaceId());

        // 查询运行主体文档，确保运行指纹对应当前有效来源资料
        SourceDocument sourceDocument = requireDocument(run.spaceId(), run.sourceDocumentId());
        if (!sourceDocument.contentHash().equals(run.sourceContentHash())) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "文档关联运行的主体内容指纹已变化");
        }

        // 保存文档关联运行历史，重复运行由调用方提供不同运行标识区分
        runRepository.save(run);
        return run;
    }

    /**
     * 将处理中的文档关联运行更新为完成或失败状态。
     *
     * @param run 带最终统计、失败阶段和完成时间的运行快照
     * @return 已更新的文档关联运行
     */
    @Override
    @Transactional
    public DocumentAssociationRun updateRun(DocumentAssociationRun run) {
        // 校验最终运行字段，避免负数统计或非法状态进入数据库
        validateRun(run);
        if ("processing".equals(run.status()) || run.completedAt() == null) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "文档关联运行尚未形成最终状态");
        }

        // 查询原始 processing 快照，防止覆盖其他文档或已结束运行
        DocumentAssociationRun existingRun = runRepository.findById(
                        run.spaceId(),
                        run.sourceDocumentId(),
                        run.id()
                )
                .orElseThrow(() -> new TipsException(ErrorCode.NOT_FOUND, "文档关联运行不存在"));
        if (!"processing".equals(existingRun.status())) {
            throw new TipsException(ErrorCode.BUSINESS_ERROR, "文档关联运行已经结束，不能重复更新");
        }

        // 校验运行主体、内容指纹和四类版本快照保持不变
        validateRunIdentity(existingRun, run);

        // 只将 processing 状态更新为完成或失败，保留创建时的固定输入快照
        int updatedRows = runRepository.update(run);
        if (updatedRows != 1) {
            throw new TipsException(ErrorCode.BUSINESS_ERROR, "文档关联运行状态更新失败");
        }
        return run;
    }

    /**
     * 查询指定文档的一次关联运行。
     *
     * @param spaceId 知识空间标识
     * @param sourceDocumentId 当前分析文档标识
     * @param runId 文档关联运行标识
     * @return 文档关联运行
     */
    @Override
    @Transactional(readOnly = true)
    public DocumentAssociationRun getRun(
            String spaceId,
            String sourceDocumentId,
            String runId
    ) {
        // 校验当前知识空间和主体文档仍可访问
        requireActiveSpace(spaceId);

        // 使用空间、文档和运行标识三重边界恢复运行
        return runRepository.findById(spaceId, sourceDocumentId, runId)
                .orElseThrow(() -> new TipsException(ErrorCode.NOT_FOUND, "文档关联运行不存在"));
    }

    /**
     * 保存一条文档关系候选或手工关系，并校验关系方向、文档归属和幂等键。
     *
     * @param relation 文档关系领域模型
     * @return 已保存的文档关系
     */
    @Override
    @Transactional
    public DocumentRelation saveRelation(DocumentRelation relation) {
        // 规范化关系两端并由服务端计算、校验稳定幂等键
        DocumentRelation normalizedRelation = prepareRelation(relation);

        // 相同空间和关系键已有记录时拒绝重复写入，保留历史关系状态
        if (relationRepository.findByRelationKey(
                normalizedRelation.spaceId(),
                normalizedRelation.relationKey()
        ).isPresent()) {
            throw new TipsException(ErrorCode.DATA_ALREADY_EXISTS, "相同版本的文档关系已经存在");
        }

        // 保存通过边界校验的文档关系候选
        relationRepository.save(normalizedRelation);
        return normalizedRelation;
    }

    /**
     * 保存一条文档关系证据，并逐字校验原文片段属于关系两端文档。
     *
     * @param evidence 文档关系证据领域模型
     * @return 已保存的文档关系证据
     */
    @Override
    @Transactional
    public DocumentRelationEvidence saveEvidence(DocumentRelationEvidence evidence) {
        // 查询关系并校验证据所属空间
        DocumentRelation relation = relationRepository.findById(
                        evidence.spaceId(),
                        evidence.documentRelationId()
                )
                .orElseThrow(() -> new TipsException(ErrorCode.NOT_FOUND, "文档关系不存在"));
        // 校验证据归属、真实分片、章节路径、逐字引用和绝对偏移
        validateEvidenceAgainstRelation(relation, evidence);

        // 保存通过逐字反查的文档关系证据
        evidenceRepository.save(evidence);
        return evidence;
    }

    /**
     * 在同一事务中幂等保存一条候选关系及其全部已校验证据。
     *
     * @param relation 待保存的候选关系；关系键可为空，由服务端计算
     * @param evidences 与候选关系一起原子保存的证据
     * @return 新保存或复用的文档关系
     */
    @Override
    @Transactional
    public DocumentRelation saveSuggestion(
            DocumentRelation relation,
            List<DocumentRelationEvidence> evidences
    ) {
        // 规范化关系并完成空间、指纹、运行归属和幂等键校验
        DocumentRelation normalizedRelation = prepareRelation(relation);
        if (evidences == null || evidences.isEmpty()) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "文档关系建议必须包含可追溯证据");
        }

        // 在写入前校验全部证据，任何一条失败都会回滚整条关系建议
        evidences.forEach(evidence -> validateEvidenceAgainstRelation(normalizedRelation, evidence));

        // 相同输入和版本的重复运行复用既有关系，避免重复建议和证据
        DocumentRelation existingRelation = relationRepository.findByRelationKey(
                normalizedRelation.spaceId(),
                normalizedRelation.relationKey()
        ).orElse(null);
        if (existingRelation != null) {
            return existingRelation;
        }

        // 原子保存通过校验的关系和全部证据
        relationRepository.save(normalizedRelation);
        evidences.forEach(evidenceRepository::save);
        return normalizedRelation;
    }

    /**
     * 记录一条文档关系审核动作，并按状态机更新关系状态。
     *
     * @param spaceId 知识空间标识
     * @param relationId 文档关系标识
     * @param action 审核动作：accept、reject 或 create
     * @param reason 审核说明
     * @param operatorName 操作者展示名称
     * @return 已保存的审核历史记录
     */
    @Override
    @Transactional
    public DocumentRelationReview reviewRelation(
            String spaceId,
            String relationId,
            String action,
            String reason,
            String operatorName
    ) {
        // 校验审核动作和操作者信息，避免写入不可解释的审核历史
        validateReviewShape(action, operatorName);

        // 校验审核所属知识空间仍然有效
        requireActiveSpace(spaceId);

        // 查询待审核关系，确保关系标识不能跨空间使用
        DocumentRelation relation = relationRepository.findById(spaceId, relationId)
                .orElseThrow(() -> new TipsException(ErrorCode.NOT_FOUND, "文档关系不存在"));

        // 根据审核动作检查当前关系状态，拒绝非法状态回退或重复确认
        String nextStatus = resolveNextStatus(relation, action);

        // 更新关系状态，关系主体、证据和版本快照保持不变
        if (!"create".equals(action)) {
            int updatedRows = relationRepository.updateStatus(spaceId, relationId, nextStatus, Instant.now());
            if (updatedRows != 1) {
                throw new TipsException(ErrorCode.BUSINESS_ERROR, "文档关系状态更新失败");
            }
        }

        DocumentRelationReview review = new DocumentRelationReview(
                buildReviewId(spaceId, relationId, action, operatorName),
                spaceId,
                relationId,
                action,
                normalizeReason(reason),
                operatorName.strip(),
                Instant.now()
        );

        // 保存不可变审核历史，不覆盖此前动作
        reviewRepository.save(review);
        return review;
    }

    /**
     * 查询一条文档关系的全部证据。
     *
     * @param spaceId 知识空间标识
     * @param relationId 文档关系标识
     * @return 文档关系证据列表
     */
    @Override
    @Transactional(readOnly = true)
    public List<DocumentRelationEvidence> listEvidence(
            String spaceId,
            String relationId
    ) {
        // 校验空间和关系存在，防止返回跨空间证据
        requireRelation(spaceId, relationId);

        // 批量查询关系下的全部证据
        return evidenceRepository.findAllByRelation(spaceId, relationId);
    }

    /**
     * 查询一条文档关系的不可变审核历史。
     *
     * @param spaceId 知识空间标识
     * @param relationId 文档关系标识
     * @return 审核历史列表
     */
    @Override
    @Transactional(readOnly = true)
    public List<DocumentRelationReview> listReviews(
            String spaceId,
            String relationId
    ) {
        // 校验空间和关系存在，防止返回跨空间审核记录
        requireRelation(spaceId, relationId);

        // 按时间倒序读取不可变审核历史
        return reviewRepository.findAllByRelation(spaceId, relationId);
    }

    /**
     * 查询指定空间和标识的文档关系。
     *
     * @param spaceId 知识空间标识
     * @param relationId 文档关系标识
     * @return 文档关系
     */
    @Override
    @Transactional(readOnly = true)
    public DocumentRelation getRelation(
            String spaceId,
            String relationId
    ) {
        // 使用统一关系边界校验阻断跨空间读取
        return requireRelation(spaceId, relationId);
    }

    /**
     * 查询一次运行新保存的全部文档关系。
     *
     * @param spaceId 知识空间标识
     * @param runId 文档关联运行标识
     * @return 运行关系列表
     */
    @Override
    @Transactional(readOnly = true)
    public List<DocumentRelation> listRelationsByRun(
            String spaceId,
            String runId
    ) {
        // 校验空间有效后按运行标识批量读取关系
        requireActiveSpace(spaceId);

        // 查询当前运行新落库的全部关系，不包含幂等复用的历史建议
        return relationRepository.findAllByRun(spaceId, runId);
    }

    /**
     * 查询一份来源资料作为任一端点的全部文档关系。
     *
     * @param spaceId 知识空间标识
     * @param documentId 来源资料标识
     * @return 当前资料相关的关系列表
     */
    @Override
    @Transactional(readOnly = true)
    public List<DocumentRelation> listRelationsByDocument(
            String spaceId,
            String documentId
    ) {
        // 校验来源资料属于当前有效空间，避免通过任意标识枚举关系
        requireDocument(spaceId, documentId);

        // 同时查询文档作为主体和客体的关系
        return relationRepository.findAllByDocument(spaceId, documentId);
    }

    /**
     * 批量查询多条文档关系的证据，供 API 避免逐关系查询。
     *
     * @param spaceId 知识空间标识
     * @param relationIds 文档关系标识
     * @return 所有匹配关系的证据
     */
    @Override
    @Transactional(readOnly = true)
    public List<DocumentRelationEvidence> listEvidence(
            String spaceId,
            List<String> relationIds
    ) {
        // 校验知识空间有效，批量查询只返回当前空间证据
        requireActiveSpace(spaceId);

        // 使用一次查询恢复多条关系证据，避免 API 响应组装形成 N+1
        return evidenceRepository.findAllByRelations(spaceId, relationIds);
    }

    /**
     * 校验运行字段和状态。
     *
     * @param run 文档关联运行
     */
    private void validateRun(DocumentAssociationRun run) {
        if (run == null
                || isBlank(run.id())
                || isBlank(run.spaceId())
                || isBlank(run.sourceDocumentId())
                || isBlank(run.sourceContentHash())
                || !RUN_STATUSES.contains(run.status())
                || isBlank(run.promptVersion())
                || isBlank(run.schemaVersion())
                || isBlank(run.candidateRecallPolicyVersion())
                || isBlank(run.associationPolicyVersion())
                || run.createdAt() == null) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "文档关联运行字段不完整或状态非法");
        }
        if (run.candidateCount() < 0
                || run.comparedCount() < 0
                || run.suggestionCount() < 0
                || run.tagCandidateCount() < 0
                || run.keywordCandidateCount() < 0
                || run.semanticCandidateCount() < 0
                || run.modelRequestCount() < 0
                || run.retryCount() < 0) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "文档关联运行统计不能为负数");
        }
        if (run.topK() != null && run.topK() <= 0) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "文档关联运行 TopK 必须大于 0");
        }
        if (run.similarityThreshold() != null
                && (run.similarityThreshold() < 0 || run.similarityThreshold() > 1)) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "文档关联运行相似度阈值必须在 0 到 1 之间");
        }
    }

    /**
     * 校验运行结束时不可变输入快照没有被调用方替换。
     *
     * @param existingRun 数据库中的 processing 运行
     * @param finalRun 调用方提交的最终运行快照
     */
    private void validateRunIdentity(
            DocumentAssociationRun existingRun,
            DocumentAssociationRun finalRun
    ) {
        boolean changed = !existingRun.sourceContentHash().equals(finalRun.sourceContentHash())
                || !existingRun.promptVersion().equals(finalRun.promptVersion())
                || !existingRun.schemaVersion().equals(finalRun.schemaVersion())
                || !existingRun.candidateRecallPolicyVersion()
                .equals(finalRun.candidateRecallPolicyVersion())
                || !existingRun.associationPolicyVersion().equals(finalRun.associationPolicyVersion())
                || !existingRun.createdAt().equals(finalRun.createdAt())
                || !java.util.Objects.equals(existingRun.topK(), finalRun.topK());
        if (changed) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "文档关联运行的输入或版本快照不能修改");
        }
    }

    /**
     * 校验关系枚举、状态、方向和基础字段。
     *
     * @param relation 文档关系
     */
    private void validateRelationShape(DocumentRelation relation) {
        if (relation == null
                || isBlank(relation.id())
                || isBlank(relation.spaceId())
                || isBlank(relation.sourceDocumentId())
                || isBlank(relation.targetDocumentId())
                || isBlank(relation.relationType())
                || isBlank(relation.direction())
                || isBlank(relation.status())
                || isBlank(relation.generationMode())
                || isBlank(relation.reason())
                || isBlank(relation.sourceContentHash())
                || isBlank(relation.targetContentHash())
                || isBlank(relation.associationPolicyVersion())
                || relation.createdAt() == null
                || relation.updatedAt() == null) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "文档关系字段不完整");
        }
        if (!RELATION_TYPES.contains(relation.relationType())) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "不支持的文档关系类型");
        }
        if (!RELATION_STATUSES.contains(relation.status())) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "不支持的文档关系状态");
        }
        if (!GENERATION_MODES.contains(relation.generationMode())) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "不支持的文档关系生成方式");
        }
        if (relation.sourceDocumentId().equals(relation.targetDocumentId())) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "文档关系不能引用自身");
        }
        if (relation.confidence() < 0 || relation.confidence() > 1) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "文档关系置信度必须在 0 到 1 之间");
        }
        boolean symmetric = "related_to".equals(relation.relationType())
                || "conflicts_with".equals(relation.relationType());
        if (symmetric && !"symmetric".equals(relation.direction())) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "对称文档关系必须使用 symmetric 方向");
        }
        if (!symmetric && !Set.of("current_to_candidate", "candidate_to_current").contains(relation.direction())) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "有向文档关系方向不合法");
        }
        if ("user".equals(relation.generationMode()) && relation.associationRunId() != null) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "手工文档关系不能绑定 AI 关联运行");
        }
        if (!"user".equals(relation.generationMode()) && relation.associationRunId() == null) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "AI 或规则文档关系必须绑定关联运行");
        }
    }

    /**
     * 规范化并校验文档关系，由服务端补齐稳定幂等键。
     *
     * @param relation 原始文档关系
     * @return 带规范化两端和稳定幂等键的关系
     */
    private DocumentRelation prepareRelation(DocumentRelation relation) {
        // 对称关系先按文档标识规范化主体、客体和内容指纹
        DocumentRelation normalizedRelation = normalizeSymmetricRelation(relation);

        // 校验关系字段、关系白名单、状态和方向组合
        validateRelationShape(normalizedRelation);

        // 校验关系所属知识空间仍然有效
        requireActiveSpace(normalizedRelation.spaceId());

        // 查询关系主体文档，防止跨空间引用或引用已删除来源
        SourceDocument sourceDocument = requireDocument(
                normalizedRelation.spaceId(),
                normalizedRelation.sourceDocumentId()
        );

        // 查询关系客体文档，确保两端来源资料属于同一空间
        SourceDocument targetDocument = requireDocument(
                normalizedRelation.spaceId(),
                normalizedRelation.targetDocumentId()
        );

        // 校验关系快照与当前文档内容一致，避免把旧版本误写成当前建议
        validateContentHashes(normalizedRelation, sourceDocument, targetDocument);

        // 校验关联运行归属，手工关系允许没有运行标识
        validateAssociationRun(normalizedRelation);

        // 由服务端计算规范化幂等键，并拒绝调用方提供的错误键
        String expectedRelationKey = buildRelationKey(normalizedRelation);
        if (!isBlank(normalizedRelation.relationKey())
                && !expectedRelationKey.equals(normalizedRelation.relationKey())) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "文档关系幂等键与关系内容不一致");
        }
        return withRelationKey(normalizedRelation, expectedRelationKey);
    }

    /**
     * 复制文档关系并补充服务端计算的稳定幂等键。
     *
     * @param relation 已规范化关系
     * @param relationKey 稳定关系键
     * @return 带关系键的不可变领域模型
     */
    private DocumentRelation withRelationKey(
            DocumentRelation relation,
            String relationKey
    ) {
        return new DocumentRelation(
                relation.id(),
                relation.spaceId(),
                relation.sourceDocumentId(),
                relation.targetDocumentId(),
                relation.relationType(),
                relation.direction(),
                relation.status(),
                relation.generationMode(),
                relation.confidence(),
                relation.reason(),
                relation.associationRunId(),
                relation.sourceContentHash(),
                relation.targetContentHash(),
                relation.associationPolicyVersion(),
                relationKey,
                relation.createdAt(),
                relation.updatedAt()
        );
    }

    /**
     * 校验证据基础字段和偏移。
     *
     * @param evidence 文档关系证据
     */
    private void validateEvidenceShape(DocumentRelationEvidence evidence) {
        if (evidence == null
                || isBlank(evidence.id())
                || isBlank(evidence.spaceId())
                || isBlank(evidence.documentRelationId())
                || isBlank(evidence.sourceDocumentId())
                || isBlank(evidence.chunkId())
                || isBlank(evidence.sectionPath())
                || isBlank(evidence.quote())
                || isBlank(evidence.evidenceRole())
                || evidence.createdAt() == null) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "文档关系证据字段不完整");
        }
        if (!EVIDENCE_ROLES.contains(evidence.evidenceRole())) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "不支持的文档关系证据角色");
        }
        if ((evidence.startOffset() != null && evidence.startOffset() < 0)
                || (evidence.endOffset() != null && evidence.endOffset() < 0)
                || (evidence.startOffset() != null
                && evidence.endOffset() != null
                && evidence.endOffset() < evidence.startOffset())) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "文档关系证据偏移不合法");
        }
    }

    /**
     * 校验证据属于关系两端的真实分片，并逐字反查章节、引用和偏移。
     *
     * @param relation 证据支撑的文档关系
     * @param evidence 待校验文档关系证据
     */
    private void validateEvidenceAgainstRelation(
            DocumentRelation relation,
            DocumentRelationEvidence evidence
    ) {
        // 校验证据字段、角色、偏移和非空原文
        validateEvidenceShape(evidence);
        if (!relation.id().equals(evidence.documentRelationId())
                || !relation.spaceId().equals(evidence.spaceId())) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "文档关系证据与关系归属不一致");
        }

        // 证据来源必须是关系主体或客体，禁止跨关系引用任意文档
        if (!relation.sourceDocumentId().equals(evidence.sourceDocumentId())
                && !relation.targetDocumentId().equals(evidence.sourceDocumentId())) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "文档关系证据必须来自关系两端文档");
        }
        if ("source".equals(evidence.evidenceRole())
                && !relation.sourceDocumentId().equals(evidence.sourceDocumentId())) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "source 证据必须来自关系主体文档");
        }
        if ("target".equals(evidence.evidenceRole())
                && !relation.targetDocumentId().equals(evidence.sourceDocumentId())) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "target 证据必须来自关系客体文档");
        }

        // 查询证据来源文档，确保逐字反查使用当前有效原文
        SourceDocument sourceDocument = requireDocument(evidence.spaceId(), evidence.sourceDocumentId());

        // 使用当前冻结的章节解析和分片规则恢复模型可引用分片
        List<DocumentChunk> chunks = documentChunker.chunk(sectionParser.parse(sourceDocument.contentText()));
        DocumentChunk chunk = chunks.stream()
                .filter(item -> item.chunkId().equals(evidence.chunkId()))
                .findFirst()
                .orElseThrow(() -> new TipsException(ErrorCode.PARAM_ERROR, "文档关系证据分片不存在"));
        if (!chunk.sectionPath().equals(evidence.sectionPath())) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "文档关系证据章节与真实分片不一致");
        }
        if (!chunk.contentText().contains(evidence.quote())) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "文档关系证据无法在指定分片中逐字反查");
        }

        // 校验证据绝对偏移与来源全文、指定分片的双重边界一致
        validateQuote(sourceDocument.contentText(), evidence.quote(), evidence.startOffset(), evidence.endOffset());
        if (evidence.startOffset() != null
                && (evidence.startOffset() < chunk.startOffset()
                || evidence.endOffset() > chunk.endOffset())) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "文档关系证据偏移超出指定分片边界");
        }
    }

    /**
     * 校验审核字段。
     *
     * @param action 审核动作
     * @param operatorName 操作者名称
     */
    private void validateReviewShape(String action, String operatorName) {
        if (!REVIEW_ACTIONS.contains(action) || isBlank(operatorName)) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "文档关系审核动作或操作者不合法");
        }
    }

    /**
     * 根据审核动作和当前状态解析下一个状态。
     *
     * @param relation 当前文档关系
     * @param action 审核动作
     * @return 下一个关系状态
     */
    private String resolveNextStatus(DocumentRelation relation, String action) {
        if ("create".equals(action)) {
            if (!"user".equals(relation.generationMode()) || !"confirmed".equals(relation.status())) {
                throw new TipsException(ErrorCode.BUSINESS_ERROR, "只有已确认的手工文档关系可以记录 create 动作");
            }
            return relation.status();
        }
        if (!"suggested".equals(relation.status())) {
            throw new TipsException(ErrorCode.BUSINESS_ERROR, "当前文档关系已审核，不能重复改变状态");
        }
        return "accept".equals(action) ? "confirmed" : "rejected";
    }

    /**
     * 校验关联运行归属。
     *
     * @param relation 文档关系
     */
    private void validateAssociationRun(DocumentRelation relation) {
        if (relation.associationRunId() == null) {
            return;
        }

        // 按空间和运行标识读取关联运行，防止客户端伪造运行归属
        DocumentAssociationRun run = runRepository.findById(relation.spaceId(), relation.associationRunId())
                .orElseThrow(() -> new TipsException(ErrorCode.NOT_FOUND, "文档关联运行不存在"));
        boolean sourceMatches = run.sourceDocumentId().equals(relation.sourceDocumentId());
        boolean targetMatches = run.sourceDocumentId().equals(relation.targetDocumentId());
        if (!sourceMatches && !targetMatches) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "文档关系两端不属于关联运行主体");
        }
    }

    /**
     * 将对称关系的两端按稳定文档标识排序，并同步交换内容指纹。
     *
     * @param relation 原始文档关系
     * @return 规范化后的文档关系
     */
    private DocumentRelation normalizeSymmetricRelation(DocumentRelation relation) {
        if (relation == null
                || !"symmetric".equals(relation.direction())
                || relation.sourceDocumentId() == null
                || relation.targetDocumentId() == null
                || relation.sourceDocumentId().compareTo(relation.targetDocumentId()) <= 0) {
            return relation;
        }
        return new DocumentRelation(
                relation.id(),
                relation.spaceId(),
                relation.targetDocumentId(),
                relation.sourceDocumentId(),
                relation.relationType(),
                relation.direction(),
                relation.status(),
                relation.generationMode(),
                relation.confidence(),
                relation.reason(),
                relation.associationRunId(),
                relation.targetContentHash(),
                relation.sourceContentHash(),
                relation.associationPolicyVersion(),
                relation.relationKey(),
                relation.createdAt(),
                relation.updatedAt()
        );
    }

    /**
     * 校验关系内容指纹快照。
     *
     * @param relation 文档关系
     * @param sourceDocument 主体文档
     * @param targetDocument 客体文档
     */
    private void validateContentHashes(
            DocumentRelation relation,
            SourceDocument sourceDocument,
            SourceDocument targetDocument
    ) {
        if (!sourceDocument.contentHash().equals(relation.sourceContentHash())
                || !targetDocument.contentHash().equals(relation.targetContentHash())) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "文档关系内容指纹已过期，请重新分析");
        }
    }

    /**
     * 校验当前知识空间有效。
     *
     * @param spaceId 知识空间标识
     */
    private void requireActiveSpace(String spaceId) {
        // 查询有效知识空间，确保新记录不会写入已删除空间
        knowledgeSpaceRepository.findActiveById(spaceId)
                .orElseThrow(() -> new TipsException(ErrorCode.NOT_FOUND, "知识空间不存在或已删除"));
    }

    /**
     * 查询当前空间有效来源资料。
     *
     * @param spaceId 知识空间标识
     * @param documentId 来源资料标识
     * @return 有效来源资料
     */
    private SourceDocument requireDocument(String spaceId, String documentId) {
        // 使用空间和文档标识双重边界读取原文
        return sourceDocumentRepository.findById(spaceId, documentId)
                .orElseThrow(() -> new TipsException(ErrorCode.NOT_FOUND, "来源资料不存在或已删除"));
    }

    /**
     * 查询当前空间文档关系。
     *
     * @param spaceId 知识空间标识
     * @param relationId 文档关系标识
     * @return 文档关系
     */
    private DocumentRelation requireRelation(String spaceId, String relationId) {
        // 校验知识空间有效，保持查询边界一致
        requireActiveSpace(spaceId);

        // 查询当前空间文档关系，拒绝跨空间读取
        return relationRepository.findById(spaceId, relationId)
                .orElseThrow(() -> new TipsException(ErrorCode.NOT_FOUND, "文档关系不存在"));
    }

    /**
     * 逐字校验证据引用和可选偏移。
     *
     * @param contentText 来源资料全文
     * @param quote 证据原文片段
     * @param startOffset 起始偏移
     * @param endOffset 结束偏移
     */
    private void validateQuote(
            String contentText,
            String quote,
            Integer startOffset,
            Integer endOffset
    ) {
        if (!contentText.contains(quote)) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "文档关系证据无法在来源原文中反查");
        }
        if (startOffset == null && endOffset == null) {
            return;
        }
        if (startOffset == null || endOffset == null || endOffset > contentText.length()) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "文档关系证据偏移不完整");
        }
        if (!contentText.substring(startOffset, endOffset).equals(quote)) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "文档关系证据偏移与原文片段不一致");
        }
    }

    /**
     * 构造稳定文档关系幂等键。
     *
     * @param relation 文档关系
     * @return SHA-256 稳定关系键
     */
    private String buildRelationKey(DocumentRelation relation) {
        String leftDocumentId = relation.sourceDocumentId();
        String rightDocumentId = relation.targetDocumentId();
        String leftHash = relation.sourceContentHash();
        String rightHash = relation.targetContentHash();
        if ("symmetric".equals(relation.direction()) && leftDocumentId.compareTo(rightDocumentId) > 0) {
            leftDocumentId = relation.targetDocumentId();
            rightDocumentId = relation.sourceDocumentId();
            leftHash = relation.targetContentHash();
            rightHash = relation.sourceContentHash();
        }
        String rawKey = String.join(
                "|",
                leftDocumentId,
                relation.relationType(),
                relation.direction(),
                rightDocumentId,
                leftHash,
                rightHash,
                relation.associationPolicyVersion()
        );
        try {
            // 使用 SHA-256 避免关系键包含原始文本或形成不可控索引长度
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(rawKey.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 不支持 SHA-256", exception);
        }
    }

    /**
     * 构造审核历史标识，避免同一动作重试造成主键冲突。
     *
     * @param spaceId 知识空间标识
     * @param relationId 文档关系标识
     * @param action 审核动作
     * @param operatorName 操作者名称
     * @return UUID 审核记录标识
     */
    private String buildReviewId(
            String spaceId,
            String relationId,
            String action,
            String operatorName
    ) {
        // 审核记录必须保留每次动作，因此使用随机 UUID 而非关系级固定键
        return java.util.UUID.randomUUID().toString();
    }

    /**
     * 清理可选审核说明。
     *
     * @param reason 原始说明
     * @return 空白说明转换为空值后的结果
     */
    private String normalizeReason(String reason) {
        return reason == null || reason.isBlank() ? null : reason.strip();
    }

    /**
     * 判断字符串是否为空或仅包含空白。
     *
     * @param value 待判断字符串
     * @return 为空或空白时返回 true
     */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

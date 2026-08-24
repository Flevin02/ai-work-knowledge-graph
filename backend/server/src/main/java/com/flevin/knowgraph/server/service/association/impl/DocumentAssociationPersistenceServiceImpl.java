package com.flevin.knowgraph.server.service.association.impl;

import com.flevin.knowgraph.common.enums.ErrorCode;
import com.flevin.knowgraph.common.exception.TipsException;
import com.flevin.knowgraph.server.model.association.DocumentAssociationRun;
import com.flevin.knowgraph.server.model.association.DocumentRelation;
import com.flevin.knowgraph.server.model.association.DocumentRelationEvidence;
import com.flevin.knowgraph.server.model.association.DocumentRelationReview;
import com.flevin.knowgraph.server.model.document.SourceDocument;
import com.flevin.knowgraph.server.repository.association.DocumentAssociationRunRepository;
import com.flevin.knowgraph.server.repository.association.DocumentRelationEvidenceRepository;
import com.flevin.knowgraph.server.repository.association.DocumentRelationRepository;
import com.flevin.knowgraph.server.repository.association.DocumentRelationReviewRepository;
import com.flevin.knowgraph.server.repository.document.SourceDocumentRepository;
import com.flevin.knowgraph.server.repository.space.KnowledgeSpaceRepository;
import com.flevin.knowgraph.server.service.association.DocumentAssociationPersistenceService;
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
     * 保存一条文档关系候选或手工关系，并校验关系方向、文档归属和幂等键。
     *
     * @param relation 文档关系领域模型
     * @return 已保存的文档关系
     */
    @Override
    @Transactional
    public DocumentRelation saveRelation(DocumentRelation relation) {
        // 对称关系先按文档标识规范化主体、客体和内容指纹，保证存储形态稳定
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

        // 计算关系规范化幂等键并校验调用方没有提交错误键
        String expectedRelationKey = buildRelationKey(normalizedRelation);
        if (!expectedRelationKey.equals(normalizedRelation.relationKey())) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "文档关系幂等键与关系内容不一致");
        }

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
        // 校验证据字段、角色、偏移和非空原文
        validateEvidenceShape(evidence);

        // 查询关系并校验证据所属空间
        DocumentRelation relation = relationRepository.findById(
                        evidence.spaceId(),
                        evidence.documentRelationId()
                )
                .orElseThrow(() -> new TipsException(ErrorCode.NOT_FOUND, "文档关系不存在"));
        if (!relation.spaceId().equals(evidence.spaceId())) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "文档关系证据与关系空间不一致");
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

        // 校验证据引用在原文中真实存在，并检查可选偏移与引用文本一致
        validateQuote(sourceDocument.contentText(), evidence.quote(), evidence.startOffset(), evidence.endOffset());

        // 保存通过逐字反查的文档关系证据
        evidenceRepository.save(evidence);
        return evidence;
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
                || isBlank(relation.relationKey())
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

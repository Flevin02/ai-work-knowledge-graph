package com.flevin.knowgraph.server.service.tag.impl;

import com.flevin.knowgraph.common.enums.ErrorCode;
import com.flevin.knowgraph.common.exception.TipsException;
import com.flevin.knowgraph.server.model.tag.DocumentTag;
import com.flevin.knowgraph.server.model.tag.DocumentTagEvidence;
import com.flevin.knowgraph.server.model.tag.DocumentTagResponse;
import com.flevin.knowgraph.server.model.tag.DocumentTagReview;
import com.flevin.knowgraph.server.model.tag.DocumentTagReviewBatchRequest;
import com.flevin.knowgraph.server.model.tag.DocumentTagReviewBatchResponse;
import com.flevin.knowgraph.server.model.tag.KnowledgeTag;
import com.flevin.knowgraph.server.model.tag.KnowledgeTagSummaryResponse;
import com.flevin.knowgraph.server.repository.document.SourceDocumentRepository;
import com.flevin.knowgraph.server.repository.projection.KnowledgeTagSummaryProjection;
import com.flevin.knowgraph.server.repository.space.KnowledgeSpaceRepository;
import com.flevin.knowgraph.server.repository.tag.DocumentTagRepository;
import com.flevin.knowgraph.server.repository.tag.DocumentTagReviewRepository;
import com.flevin.knowgraph.server.service.tag.DocumentTagPersistenceService;
import com.flevin.knowgraph.server.service.tag.DocumentTagService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 文档标签查询和人工审核应用服务实现。
 */
@Service
@RequiredArgsConstructor
public class DocumentTagServiceImpl implements DocumentTagService {

    private static final String LOCAL_OPERATOR = "local-user";
    private static final String SUGGESTED_STATUS = "suggested";
    private static final int MAX_BATCH_SIZE = 100;

    private final KnowledgeSpaceRepository knowledgeSpaceRepository;
    private final SourceDocumentRepository sourceDocumentRepository;
    private final DocumentTagRepository documentTagRepository;
    private final DocumentTagReviewRepository documentTagReviewRepository;
    private final DocumentTagPersistenceService persistenceService;

    /**
     * 查询一份来源资料的标签、证据和不可变审核历史。
     *
     * @param spaceId 知识空间标识
     * @param documentId 来源资料标识
     * @return 文档标签响应列表
     */
    @Override
    @Transactional(readOnly = true)
    public List<DocumentTagResponse> listDocumentTags(
            String spaceId,
            String documentId
    ) {
        // 校验当前空间和来源资料仍有效，阻断跨空间标签读取
        requireDocument(spaceId, documentId);

        // 一次查询当前来源资料的全部标签状态
        List<DocumentTag> documentTags = documentTagRepository.findAllByDocument(spaceId, documentId);

        // 批量组装标签定义、证据和审核历史，供刷新后完整恢复
        return toResponses(spaceId, documentTags);
    }

    /**
     * 批量采纳或拒绝一份来源资料下的 suggested 标签。
     *
     * @param spaceId 知识空间标识
     * @param documentId 路径中的来源资料标识
     * @param request 服务端文档标签标识和审核动作
     * @return 审核统计和最新标签快照
     */
    @Override
    @Transactional
    public DocumentTagReviewBatchResponse reviewDocumentTags(
            String spaceId,
            String documentId,
            DocumentTagReviewBatchRequest request
    ) {
        // 校验批次基本形状，保证 Service 直接调用也遵守 Controller 契约
        validateReviewRequest(request);

        // 校验当前空间和来源资料仍有效，阻断跨空间或已删除资料审核
        requireDocument(spaceId, documentId);

        Set<String> documentTagIds = request.reviews().stream()
                .map(DocumentTagReviewBatchRequest.Item::documentTagId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (documentTagIds.size() != request.reviews().size()) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "同一批次不能重复审核同一文档标签");
        }

        // 一次读取当前资料的全部标签，验证客户端不能跨文档提交服务端标识
        Map<String, DocumentTag> documentTagById = documentTagRepository
                .findAllByDocument(spaceId, documentId)
                .stream()
                .collect(Collectors.toMap(DocumentTag::id, Function.identity()));
        request.reviews().forEach(review -> validateReviewTarget(documentTagById, review));

        int acceptedCount = 0;
        int rejectedCount = 0;
        for (DocumentTagReviewBatchRequest.Item review : request.reviews()) {
            // 仅按服务端文档标签标识执行状态机，不接受客户端覆盖标签内容或证据
            persistenceService.reviewDocumentTag(
                    spaceId,
                    review.documentTagId(),
                    review.action().getValue(),
                    review.reason(),
                    LOCAL_OPERATOR
            );
            if (review.action() == DocumentTagReviewBatchRequest.Action.ACCEPT) {
                acceptedCount++;
            } else {
                rejectedCount++;
            }
        }

        // 在同一事务中恢复本批审核后的标签状态、证据和不可变历史
        List<DocumentTag> reviewedTags = documentTagRepository.findAllByDocument(spaceId, documentId)
                .stream()
                .filter(documentTag -> documentTagIds.contains(documentTag.id()))
                .toList();
        return new DocumentTagReviewBatchResponse(
                acceptedCount,
                rejectedCount,
                toResponses(spaceId, reviewedTags)
        );
    }

    /**
     * 查询当前空间可参与筛选的已确认标签和有效文档数量。
     *
     * @param spaceId 知识空间标识
     * @return 已确认标签摘要列表
     */
    @Override
    @Transactional(readOnly = true)
    public List<KnowledgeTagSummaryResponse> listConfirmedTags(String spaceId) {
        // 校验知识空间仍有效，避免跨空间聚合标签统计
        knowledgeSpaceRepository.findActiveById(spaceId)
                .orElseThrow(() -> new TipsException(ErrorCode.NOT_FOUND, "知识空间不存在"));

        // 使用数据库 Join 聚合查询 confirmed 标签和有效文档数量
        return documentTagRepository.findConfirmedSummaries(spaceId)
                .stream()
                .map(this::toSummaryResponse)
                .toList();
    }

    /**
     * 校验批量审核请求基本形状。
     *
     * @param request 批量审核请求
     */
    private void validateReviewRequest(DocumentTagReviewBatchRequest request) {
        if (request == null
                || request.reviews() == null
                || request.reviews().isEmpty()
                || request.reviews().size() > MAX_BATCH_SIZE) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "文档标签审核批次必须包含 1 到 100 条决定");
        }
        boolean invalidItem = request.reviews().stream().anyMatch(review -> review == null
                || review.documentTagId() == null
                || review.documentTagId().isBlank()
                || review.action() == null
                || review.reason() != null && review.reason().length() > 500);
        if (invalidItem) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "文档标签审核决定字段不完整或说明过长");
        }
    }

    /**
     * 校验单条审核目标属于当前资料且仍为 suggested。
     *
     * @param documentTagById 当前资料标签索引
     * @param review 单条审核决定
     */
    private void validateReviewTarget(
            Map<String, DocumentTag> documentTagById,
            DocumentTagReviewBatchRequest.Item review
    ) {
        DocumentTag documentTag = documentTagById.get(review.documentTagId());
        if (documentTag == null) {
            throw new TipsException(ErrorCode.NOT_FOUND, "文档标签不属于当前来源资料");
        }
        if (!SUGGESTED_STATUS.equals(documentTag.status())) {
            throw new TipsException(ErrorCode.DATA_ALREADY_EXISTS, "文档标签已完成审核，不能重复操作");
        }
    }

    /**
     * 批量组装文档标签、标签定义、证据和审核历史响应。
     *
     * @param spaceId 知识空间标识
     * @param documentTags 文档标签关系列表
     * @return 完整文档标签响应
     */
    private List<DocumentTagResponse> toResponses(
            String spaceId,
            List<DocumentTag> documentTags
    ) {
        List<String> tagIds = documentTags.stream().map(DocumentTag::tagId).distinct().toList();
        List<String> documentTagIds = documentTags.stream().map(DocumentTag::id).toList();

        // 批量读取标签定义并建立标识索引，避免逐标签查询数据库
        Map<String, KnowledgeTag> tagById = documentTagRepository.findTagsByIds(spaceId, tagIds)
                .stream()
                .collect(Collectors.toMap(KnowledgeTag::id, Function.identity()));

        // 批量读取全部逐字证据并按文档标签关系分组
        Map<String, List<DocumentTagEvidence>> evidenceByDocumentTag = documentTagRepository
                .findEvidenceByDocumentTags(spaceId, documentTagIds)
                .stream()
                .collect(Collectors.groupingBy(
                        DocumentTagEvidence::documentTagId,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        // 批量读取不可变审核历史并按文档标签关系分组
        Map<String, List<DocumentTagReview>> reviewByDocumentTag = documentTagReviewRepository
                .findAllByDocumentTags(spaceId, documentTagIds)
                .stream()
                .collect(Collectors.groupingBy(
                        DocumentTagReview::documentTagId,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        return documentTags.stream()
                .map(documentTag -> toResponse(
                        documentTag,
                        tagById.get(documentTag.tagId()),
                        evidenceByDocumentTag.getOrDefault(documentTag.id(), List.of()),
                        reviewByDocumentTag.getOrDefault(documentTag.id(), List.of())
                ))
                .toList();
    }

    /**
     * 转换一条文档标签聚合为 API 响应。
     *
     * @param documentTag 文档标签关系
     * @param tag 标签定义
     * @param evidences 已校验证据
     * @param reviews 不可变审核历史
     * @return 文档标签响应
     */
    private DocumentTagResponse toResponse(
            DocumentTag documentTag,
            KnowledgeTag tag,
            List<DocumentTagEvidence> evidences,
            List<DocumentTagReview> reviews
    ) {
        if (tag == null) {
            throw new TipsException(ErrorCode.BUSINESS_ERROR, "文档标签字典数据不完整");
        }
        List<DocumentTagResponse.Evidence> evidenceResponses = evidences.stream()
                .map(evidence -> new DocumentTagResponse.Evidence(
                        evidence.id(),
                        evidence.sourceDocumentId(),
                        evidence.chunkId(),
                        evidence.sectionPath(),
                        evidence.quote(),
                        evidence.startOffset(),
                        evidence.endOffset()
                ))
                .toList();
        List<DocumentTagResponse.Review> reviewResponses = reviews.stream()
                .map(review -> new DocumentTagResponse.Review(
                        review.id(),
                        review.action(),
                        review.reason(),
                        review.operatorName(),
                        review.createdAt()
                ))
                .toList();
        return new DocumentTagResponse(
                documentTag.id(),
                documentTag.tagId(),
                tag.name(),
                tag.normalizedKey(),
                documentTag.sourceType(),
                documentTag.status(),
                documentTag.confidence(),
                documentTag.extractionRunId(),
                evidenceResponses,
                reviewResponses,
                documentTag.createdAt(),
                documentTag.updatedAt()
        );
    }

    /**
     * 转换数据库标签统计投影为 API 响应。
     *
     * @param projection 标签统计投影
     * @return 已确认标签摘要响应
     */
    private KnowledgeTagSummaryResponse toSummaryResponse(KnowledgeTagSummaryProjection projection) {
        return new KnowledgeTagSummaryResponse(
                projection.getTagId(),
                projection.getName(),
                projection.getNormalizedKey(),
                projection.getDocumentCount(),
                Instant.parse(projection.getUpdatedAt())
        );
    }

    /**
     * 校验知识空间和来源资料仍有效。
     *
     * @param spaceId 知识空间标识
     * @param documentId 来源资料标识
     */
    private void requireDocument(
            String spaceId,
            String documentId
    ) {
        // 校验知识空间有效，避免已删除空间查询或审核标签
        knowledgeSpaceRepository.findActiveById(spaceId)
                .orElseThrow(() -> new TipsException(ErrorCode.NOT_FOUND, "知识空间不存在"));

        // 使用空间和资料标识双重边界读取来源资料
        sourceDocumentRepository.findById(spaceId, documentId)
                .orElseThrow(() -> new TipsException(ErrorCode.NOT_FOUND, "来源资料不存在或已删除"));
    }
}

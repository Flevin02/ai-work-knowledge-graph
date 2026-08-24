package com.flevin.knowgraph.server.repository.association;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.flevin.knowgraph.server.model.association.DocumentRelationReview;
import com.flevin.knowgraph.server.repository.entity.DocumentRelationReviewEntity;
import com.flevin.knowgraph.server.repository.mapper.DocumentRelationReviewMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * 文档关系审核历史数据访问对象，保持审核动作不可变。
 */
@Repository
@RequiredArgsConstructor
public class DocumentRelationReviewRepository {

    private final DocumentRelationReviewMapper mapper;

    /**
     * 保存一条不可变文档关系审核动作。
     *
     * @param review 文档关系审核历史领域模型
     */
    public void save(DocumentRelationReview review) {
        // 使用插入而非更新保留完整审核历史
        mapper.insert(toEntity(review));
    }

    /**
     * 查询指定空间和关系的审核历史。
     *
     * @param spaceId 知识空间标识
     * @param relationId 文档关系标识
     * @return 按时间倒序返回审核历史
     */
    public List<DocumentRelationReview> findAllByRelation(
            String spaceId,
            String relationId
    ) {
        // 按空间和关系边界读取不可变审核动作
        return mapper.selectList(
                        Wrappers.<DocumentRelationReviewEntity>lambdaQuery()
                                .eq(DocumentRelationReviewEntity::getSpaceId, spaceId)
                                .eq(DocumentRelationReviewEntity::getDocumentRelationId, relationId)
                                .orderByDesc(DocumentRelationReviewEntity::getCreatedAt)
                                .orderByDesc(DocumentRelationReviewEntity::getId)
                ).stream()
                .map(this::toDomain)
                .toList();
    }

    /**
     * 将持久化实体转换为审核历史领域模型。
     *
     * @param entity MyBatis-Plus 持久化实体
     * @return 审核历史领域模型
     */
    private DocumentRelationReview toDomain(DocumentRelationReviewEntity entity) {
        return new DocumentRelationReview(
                entity.getId(),
                entity.getSpaceId(),
                entity.getDocumentRelationId(),
                entity.getAction(),
                entity.getReason(),
                entity.getOperatorName(),
                Instant.parse(entity.getCreatedAt())
        );
    }

    /**
     * 将审核历史领域模型转换为 MyBatis-Plus 实体。
     *
     * @param review 审核历史领域模型
     * @return MyBatis-Plus 持久化实体
     */
    private DocumentRelationReviewEntity toEntity(DocumentRelationReview review) {
        DocumentRelationReviewEntity entity = new DocumentRelationReviewEntity();
        entity.setId(review.id());
        entity.setSpaceId(review.spaceId());
        entity.setDocumentRelationId(review.documentRelationId());
        entity.setAction(review.action());
        entity.setReason(review.reason());
        entity.setOperatorName(review.operatorName());
        entity.setCreatedAt(review.createdAt().toString());
        return entity;
    }
}

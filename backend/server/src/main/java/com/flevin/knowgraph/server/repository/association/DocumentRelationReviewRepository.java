package com.flevin.knowgraph.server.repository.association;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.flevin.knowgraph.server.model.association.DocumentRelationReview;
import com.flevin.knowgraph.server.repository.entity.DocumentRelationReviewEntity;
import com.flevin.knowgraph.server.repository.mapper.DocumentRelationReviewMapper;
import com.flevin.knowgraph.server.repository.mapping.DocumentRelationReviewEntityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 文档关系审核历史数据访问对象，保持审核动作不可变。
 */
@Repository
@RequiredArgsConstructor
public class DocumentRelationReviewRepository {

    private final DocumentRelationReviewMapper mapper;
    private final DocumentRelationReviewEntityMapper entityMapper;

    /**
     * 保存一条不可变文档关系审核动作。
     *
     * @param review 文档关系审核历史领域模型
     */
    public void save(DocumentRelationReview review) {
        // 使用插入而非更新保留完整审核历史
        mapper.insert(entityMapper.toEntity(review));
    }

    /**
     * 查询指定空间和关系的审核历史。
     *
     * @param spaceId 知识空间标识
     * @param relationId 文档关系标识
     * @return 按时间倒序返回审核历史
     */
    public List<DocumentRelationReview> findAllByRelation(
            Long spaceId,
            Long relationId
    ) {
        // 按空间和关系边界读取不可变审核动作
        return mapper.selectList(
                        Wrappers.<DocumentRelationReviewEntity>lambdaQuery()
                                .eq(DocumentRelationReviewEntity::getSpaceId, spaceId)
                                .eq(DocumentRelationReviewEntity::getDocumentRelationId, relationId)
                                .orderByDesc(DocumentRelationReviewEntity::getCreatedAt)
                                .orderByDesc(DocumentRelationReviewEntity::getId)
                ).stream()
                .map(entityMapper::toDomain)
                .toList();
    }

}

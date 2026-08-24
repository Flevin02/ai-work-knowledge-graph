package com.flevin.knowgraph.server.repository.tag;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.flevin.knowgraph.server.model.tag.DocumentTagReview;
import com.flevin.knowgraph.server.repository.entity.DocumentTagReviewEntity;
import com.flevin.knowgraph.server.repository.mapper.DocumentTagReviewMapper;
import com.flevin.knowgraph.server.repository.mapping.DocumentTagReviewEntityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 文档标签审核历史数据访问对象，保持审核动作不可变。
 */
@Repository
@RequiredArgsConstructor
public class DocumentTagReviewRepository {

    private final DocumentTagReviewMapper mapper;
    private final DocumentTagReviewEntityMapper entityMapper;

    /**
     * 保存一条不可变文档标签审核动作。
     *
     * @param review 文档标签审核历史领域模型
     */
    public void save(DocumentTagReview review) {
        // 将审核历史领域模型转换为持久化实体
        DocumentTagReviewEntity entity = entityMapper.toEntity(review);

        // 使用插入而非更新保留完整审核事实
        mapper.insert(entity);
    }

    /**
     * 批量查询多条文档标签关系的不可变审核历史。
     *
     * @param spaceId 知识空间标识
     * @param documentTagIds 文档标签关系标识列表
     * @return 按标签、时间和标识排序的审核历史
     */
    public List<DocumentTagReview> findAllByDocumentTags(
            String spaceId,
            List<String> documentTagIds
    ) {
        if (documentTagIds.isEmpty()) {
            return List.of();
        }

        // 使用空间和文档标签标识集合一次读取全部审核历史，避免响应恢复产生 N+1
        return mapper.selectList(
                        Wrappers.<DocumentTagReviewEntity>lambdaQuery()
                                .eq(DocumentTagReviewEntity::getSpaceId, spaceId)
                                .in(DocumentTagReviewEntity::getDocumentTagId, documentTagIds)
                                .orderByAsc(DocumentTagReviewEntity::getDocumentTagId)
                                .orderByDesc(DocumentTagReviewEntity::getCreatedAt)
                                .orderByDesc(DocumentTagReviewEntity::getId)
                ).stream()
                .map(entityMapper::toDomain)
                .toList();
    }
}

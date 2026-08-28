package com.flevin.knowgraph.server.repository.document;

import com.flevin.knowgraph.server.model.ai.rag.DocumentChunkFact;
import com.flevin.knowgraph.server.repository.entity.DocumentChunkEntity;
import com.flevin.knowgraph.server.repository.mapper.DocumentChunkMapper;
import com.flevin.knowgraph.server.repository.mapping.DocumentChunkEntityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

import static com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery;

/**
 * 来源资料分片事实数据访问对象，负责按空间、资料和策略版本读取可重建分片。
 */
@Repository
@RequiredArgsConstructor
public class DocumentChunkRepository {

    private final DocumentChunkMapper mapper;
    private final DocumentChunkEntityMapper entityMapper;

    /**
     * 保存一条分片事实。
     *
     * @param fact 分片领域事实
     */
    public void save(DocumentChunkFact fact) {
        // 将分片领域事实转换为 MyBatis-Plus 实体并写入事实表
        mapper.insertIfAbsent(entityMapper.toEntity(fact));
    }

    /**
     * 查询当前空间指定来源资料的分片，按原文顺序返回。
     *
     * @param spaceId 知识空间标识
     * @param sourceDocumentId 来源资料标识
     * @param chunkVersion 分片策略版本
     * @return 分片事实列表
     */
    public List<DocumentChunkFact> findByDocument(
            Long spaceId,
            Long sourceDocumentId,
            String chunkVersion
    ) {
        // 按空间、资料和分片策略版本读取可用于重建的分片事实
        List<DocumentChunkEntity> entities = mapper.selectList(
                lambdaQuery(DocumentChunkEntity.class)
                        .eq(DocumentChunkEntity::getSpaceId, spaceId)
                        .eq(DocumentChunkEntity::getSourceDocumentId, sourceDocumentId)
                        .eq(DocumentChunkEntity::getChunkVersion, chunkVersion)
                        .orderByAsc(DocumentChunkEntity::getDocumentOrdinal)
                        .orderByAsc(DocumentChunkEntity::getId)
        );

        // 将 ORM 实体转换为领域事实，避免上层感知 MyBatis-Plus
        return entities.stream().map(entityMapper::toDomain).toList();
    }
}

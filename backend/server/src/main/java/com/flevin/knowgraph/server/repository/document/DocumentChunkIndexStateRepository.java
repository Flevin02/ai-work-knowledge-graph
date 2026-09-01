package com.flevin.knowgraph.server.repository.document;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.flevin.knowgraph.server.model.ai.rag.DocumentChunkIndexStateFact;
import com.flevin.knowgraph.server.repository.entity.DocumentChunkIndexStateEntity;
import com.flevin.knowgraph.server.repository.mapper.DocumentChunkIndexStateMapper;
import com.flevin.knowgraph.server.repository.mapping.DocumentChunkIndexStateEntityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery;

/**
 * 分片向量事实数据访问对象，负责读取可用于精确 COSINE 的已就绪向量。
 */
@Repository
@RequiredArgsConstructor
public class DocumentChunkIndexStateRepository {

    private final DocumentChunkIndexStateMapper mapper;
    private final DocumentChunkIndexStateEntityMapper entityMapper;

    /**
     * 保存一条已校验的分片向量事实。
     *
     * @param fact 分片向量索引状态事实
     */
    public void save(DocumentChunkIndexStateFact fact) {
        // 将领域向量事实转换为实体并写入或恢复为就绪状态
        mapper.upsertReady(entityMapper.toEntity(fact));
    }

    /**
     * 在单一事务中保存一批已经完整校验的向量事实，避免数据库异常留下半批结果。
     *
     * @param facts 已校验且属于同一索引任务的向量事实
     */
    @Transactional
    public void saveAll(List<DocumentChunkIndexStateFact> facts) {
        // 逐条执行可重试写入；事务保证任一数据库失败时整批回滚
        facts.forEach(this::save);
    }

    /**
     * 查询指定模型边界下可参与精确召回的向量事实。
     *
     * @param spaceId 知识空间标识
     * @param chunkVersion 分片策略版本
     * @param embeddingProvider Embedding 供应商或 Fake 标识
     * @param embeddingModel Embedding 模型标识
     * @param embeddingVersion Embedding 版本标识
     * @param dimension 向量维度
     * @return 已就绪的向量事实
     */
    public List<DocumentChunkIndexStateFact> findReady(
            Long spaceId,
            String chunkVersion,
            String embeddingProvider,
            String embeddingModel,
            String embeddingVersion,
            int dimension
    ) {
        // 按空间、状态、模型和维度过滤，禁止不同版本向量混用
        List<DocumentChunkIndexStateEntity> entities = mapper.selectList(
                lambdaQuery(DocumentChunkIndexStateEntity.class)
                        .eq(DocumentChunkIndexStateEntity::getSpaceId, spaceId)
                        .eq(DocumentChunkIndexStateEntity::getChunkVersion, chunkVersion)
                        .eq(DocumentChunkIndexStateEntity::getEmbeddingProvider, embeddingProvider)
                        .eq(DocumentChunkIndexStateEntity::getEmbeddingModel, embeddingModel)
                        .eq(DocumentChunkIndexStateEntity::getEmbeddingVersion, embeddingVersion)
                        .eq(DocumentChunkIndexStateEntity::getDimension, dimension)
                        .eq(DocumentChunkIndexStateEntity::getStatus, "ready")
                        .orderByAsc(DocumentChunkIndexStateEntity::getSourceDocumentId)
                        .orderByAsc(DocumentChunkIndexStateEntity::getChunkRecordId)
        );

        // 将 ORM 实体转换为领域事实，交由检索服务执行余弦计算
        return entities.stream().map(entityMapper::toDomain).toList();
    }

    /**
     * 查询一份来源资料在指定分片和模型边界下已经就绪的向量事实。
     *
     * @param spaceId 知识空间标识
     * @param sourceDocumentId 来源资料标识
     * @param chunkVersion 分片策略版本
     * @param embeddingProvider Embedding 供应商或 Fake 标识
     * @param embeddingModel Embedding 模型标识
     * @param embeddingVersion Embedding 版本标识
     * @param dimension 向量维度
     * @return 当前资料可直接复用的已就绪向量事实
     */
    public List<DocumentChunkIndexStateFact> findReadyByDocument(
            Long spaceId,
            Long sourceDocumentId,
            String chunkVersion,
            String embeddingProvider,
            String embeddingModel,
            String embeddingVersion,
            int dimension
    ) {
        // 按资料、分片版本和完整模型描述读取可复用向量，避免扫描空间内其他资料
        List<DocumentChunkIndexStateEntity> entities = mapper.selectList(
                lambdaQuery(DocumentChunkIndexStateEntity.class)
                        .eq(DocumentChunkIndexStateEntity::getSpaceId, spaceId)
                        .eq(DocumentChunkIndexStateEntity::getSourceDocumentId, sourceDocumentId)
                        .eq(DocumentChunkIndexStateEntity::getChunkVersion, chunkVersion)
                        .eq(DocumentChunkIndexStateEntity::getEmbeddingProvider, embeddingProvider)
                        .eq(DocumentChunkIndexStateEntity::getEmbeddingModel, embeddingModel)
                        .eq(DocumentChunkIndexStateEntity::getEmbeddingVersion, embeddingVersion)
                        .eq(DocumentChunkIndexStateEntity::getDimension, dimension)
                        .eq(DocumentChunkIndexStateEntity::getStatus, "ready")
                        .orderByAsc(DocumentChunkIndexStateEntity::getChunkRecordId)
        );

        // 将 ORM 实体转换为领域事实，供索引服务按内容指纹判断复用
        return entities.stream().map(entityMapper::toDomain).toList();
    }

    /**
     * 统计一份来源资料当前 ready 的向量事实数量。
     *
     * @param spaceId 知识空间标识
     * @param sourceDocumentId 来源资料标识
     * @return ready 索引状态行数
     */
    public long countReadyByDocument(
            Long spaceId,
            Long sourceDocumentId
    ) {
        return mapper.selectCount(
                lambdaQuery(DocumentChunkIndexStateEntity.class)
                        .eq(DocumentChunkIndexStateEntity::getSpaceId, spaceId)
                        .eq(DocumentChunkIndexStateEntity::getSourceDocumentId, sourceDocumentId)
                        .eq(DocumentChunkIndexStateEntity::getStatus, "ready")
        );
    }

    /**
     * 将一份来源资料的 ready 索引状态标记为 stale。
     *
     * <p>用于来源资料内容更新后冻结基于旧分片哈希的向量事实；
     * 向量事实按内容哈希版本化保留，可随时追溯或重建。</p>
     *
     * @param spaceId 知识空间标识
     * @param sourceDocumentId 来源资料标识
     * @param updatedAt 失效时间
     * @return 实际标记为 stale 的行数
     */
    public int markStaleByDocument(
            Long spaceId,
            Long sourceDocumentId,
            Instant updatedAt
    ) {
        DocumentChunkIndexStateEntity entity = new DocumentChunkIndexStateEntity();
        entity.setStatus("stale");
        entity.setUpdatedAt(updatedAt.toString());

        // 只迁移 ready 状态，failed 与已 stale 的索引状态保持不变
        return mapper.update(
                entity,
                Wrappers.<DocumentChunkIndexStateEntity>lambdaUpdate()
                        .eq(DocumentChunkIndexStateEntity::getSpaceId, spaceId)
                        .eq(DocumentChunkIndexStateEntity::getSourceDocumentId, sourceDocumentId)
                        .eq(DocumentChunkIndexStateEntity::getStatus, "ready")
        );
    }
}

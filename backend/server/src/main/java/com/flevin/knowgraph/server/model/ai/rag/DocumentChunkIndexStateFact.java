package com.flevin.knowgraph.server.model.ai.rag;

import com.flevin.knowgraph.server.model.ai.embedding.EmbeddingVector;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * 分片向量事实的可重建索引状态。
 *
 * @param id 索引状态标识
 * @param spaceId 知识空间标识
 * @param sourceDocumentId 来源资料标识
 * @param chunkRecordId 分片事实标识
 * @param chunkId 文档内稳定分片标识
 * @param contentHash 向量对应的分片内容指纹
 * @param chunkVersion 分片策略版本
 * @param embeddingProvider Embedding 供应商或 Fake 标识
 * @param embeddingModel Embedding 模型标识
 * @param embeddingVersion Embedding 版本标识
 * @param dimension 向量维度
 * @param vector 向量事实
 * @param status 索引状态
 * @param errorMessage 失败原因
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record DocumentChunkIndexStateFact(
        Long id,
        Long spaceId,
        Long sourceDocumentId,
        Long chunkRecordId,
        String chunkId,
        String contentHash,
        String chunkVersion,
        String embeddingProvider,
        String embeddingModel,
        String embeddingVersion,
        int dimension,
        EmbeddingVector vector,
        String status,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt
) {

    private static final Set<String> SUPPORTED_STATUSES = Set.of("ready", "failed", "stale");

    public DocumentChunkIndexStateFact {
        if (id == null || id <= 0
                || spaceId == null || spaceId <= 0
                || sourceDocumentId == null || sourceDocumentId <= 0
                || chunkRecordId == null || chunkRecordId <= 0) {
            throw new IllegalArgumentException("分片索引事实标识必须大于零");
        }
        if (isBlank(chunkId) || isBlank(contentHash) || isBlank(chunkVersion)) {
            throw new IllegalArgumentException("分片索引标识、内容指纹和策略版本不能为空");
        }
        if (isBlank(embeddingProvider) || isBlank(embeddingModel) || isBlank(embeddingVersion)) {
            throw new IllegalArgumentException("Embedding 供应商、模型和版本不能为空");
        }
        // 拒绝缺少向量的索引事实，当前表仅保存可重建的完整向量数据
        Objects.requireNonNull(vector, "分片索引向量不能为空");
        if (dimension <= 0 || vector.dimension() != dimension) {
            throw new IllegalArgumentException("分片索引向量维度与记录维度不一致");
        }
        if (!SUPPORTED_STATUSES.contains(status)) {
            throw new IllegalArgumentException("分片索引状态只允许 ready、failed 或 stale");
        }
        Objects.requireNonNull(createdAt, "分片索引创建时间不能为空");
        Objects.requireNonNull(updatedAt, "分片索引更新时间不能为空");
    }

    /**
     * 判断索引事实文本字段是否为空。
     *
     * @param value 待校验文本
     * @return 空值或空白文本返回 true
     */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

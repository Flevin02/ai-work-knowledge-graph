package com.flevin.knowgraph.server.model.ai.embedding;

import java.util.Objects;

/**
 * 可参与精确语义召回的分片向量事实。
 *
 * @param spaceId 知识空间标识
 * @param sourceDocumentId 来源资料标识
 * @param chunkRecordId 分片事实数据库标识
 * @param chunkId 文档内稳定分片标识
 * @param contentHash 分片内容指纹
 * @param chunkVersion 分片策略版本
 * @param descriptor 生成该向量的模型描述
 * @param vector 分片向量
 */
public record SemanticChunkVector(
        Long spaceId,
        Long sourceDocumentId,
        Long chunkRecordId,
        String chunkId,
        String contentHash,
        String chunkVersion,
        EmbeddingModelDescriptor descriptor,
        EmbeddingVector vector
) {

    public SemanticChunkVector {
        Objects.requireNonNull(spaceId, "语义分片空间标识不能为空");
        Objects.requireNonNull(sourceDocumentId, "语义分片来源资料标识不能为空");
        Objects.requireNonNull(chunkRecordId, "语义分片事实标识不能为空");
        if (chunkId == null || chunkId.isBlank()) {
            throw new IllegalArgumentException("语义分片标识不能为空");
        }
        if (contentHash == null || contentHash.isBlank()) {
            throw new IllegalArgumentException("语义分片内容指纹不能为空");
        }
        if (chunkVersion == null || chunkVersion.isBlank()) {
            throw new IllegalArgumentException("语义分片策略版本不能为空");
        }
        Objects.requireNonNull(descriptor, "语义分片模型描述不能为空");
        Objects.requireNonNull(vector, "语义分片向量不能为空");
        if (vector.dimension() != descriptor.dimension()) {
            throw new IllegalArgumentException("语义分片向量维度与模型描述不一致");
        }
    }
}

package com.flevin.knowgraph.server.model.ai.embedding;

/**
 * 一份来源资料的分片向量索引结果。
 *
 * @param descriptor 本次使用的 Embedding 模型描述
 * @param chunkVersion 分片策略版本
 * @param totalChunkCount 当前版本分片总数
 * @param reusedCount 直接复用的已就绪向量数量
 * @param indexedCount 本次新生成并写入的向量数量
 */
public record DocumentEmbeddingIndexResult(
        EmbeddingModelDescriptor descriptor,
        String chunkVersion,
        int totalChunkCount,
        int reusedCount,
        int indexedCount
) {
}

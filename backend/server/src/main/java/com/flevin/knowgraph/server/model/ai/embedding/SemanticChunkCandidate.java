package com.flevin.knowgraph.server.model.ai.embedding;

/**
 * 精确 COSINE 召回的一条分片候选。
 *
 * @param spaceId 知识空间标识
 * @param sourceDocumentId 来源资料标识
 * @param chunkRecordId 分片事实数据库标识
 * @param chunkId 文档内稳定分片标识
 * @param contentHash 分片内容指纹
 * @param chunkVersion 分片策略版本
 * @param score 余弦相似度
 */
public record SemanticChunkCandidate(
        Long spaceId,
        Long sourceDocumentId,
        Long chunkRecordId,
        String chunkId,
        String contentHash,
        String chunkVersion,
        double score
) {
}

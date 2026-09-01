package com.flevin.knowgraph.server.model.ai.embedding;

/**
 * 独立语义召回的一条文档级候选。
 *
 * @param sourceDocumentId 候选来源资料标识；语义召回在分片层排除主体资料，因此不会出现当前主体
 * @param bestChunkScore 该文档所有分片与主体查询分片之间的最高余弦相似度（max 池化）
 * @param bestChunkId 取得最高相似度的文档内分片标识
 * @param bestChunkRecordId 取得最高相似度的分片事实标识
 * @param rank 在本次语义召回结果中的 1-based 排名
 */
public record SemanticDocumentCandidate(
        Long sourceDocumentId,
        double bestChunkScore,
        String bestChunkId,
        Long bestChunkRecordId,
        int rank
) {
}

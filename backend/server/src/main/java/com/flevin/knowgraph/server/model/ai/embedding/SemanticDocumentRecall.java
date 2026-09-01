package com.flevin.knowgraph.server.model.ai.embedding;

import java.util.List;

/**
 * 一份来源资料的独立语义召回结果，只服务实验对照，不接入默认文档关联候选。
 *
 * @param spaceId 知识空间标识
 * @param sourceDocumentId 当前作为召回主体的来源资料标识
 * @param semanticRecallPolicyVersion 语义召回策略版本
 * @param chunkVersion 本次参与召回的分片策略版本
 * @param descriptor 查询与索引向量共同使用的 Embedding 模型描述
 * @param topK 文档级候选数量上限
 * @param candidates 按最高分片相似度降序、来源标识升序稳定排列的文档候选
 * @param queryChunkCount 参与查询的主体分片向量数量
 */
public record SemanticDocumentRecall(
        Long spaceId,
        Long sourceDocumentId,
        String semanticRecallPolicyVersion,
        String chunkVersion,
        EmbeddingModelDescriptor descriptor,
        int topK,
        List<SemanticDocumentCandidate> candidates,
        int queryChunkCount
) {

    public SemanticDocumentRecall {
        candidates = List.copyOf(candidates);
    }
}

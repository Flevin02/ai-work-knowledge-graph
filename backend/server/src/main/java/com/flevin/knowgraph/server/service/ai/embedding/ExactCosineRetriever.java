package com.flevin.knowgraph.server.service.ai.embedding;

import com.flevin.knowgraph.server.model.ai.embedding.EmbeddingModelDescriptor;
import com.flevin.knowgraph.server.model.ai.embedding.EmbeddingVector;
import com.flevin.knowgraph.server.model.ai.embedding.SemanticChunkCandidate;
import com.flevin.knowgraph.server.model.ai.embedding.SemanticChunkVector;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 小规模分片实验使用的精确 COSINE 检索器。
 *
 * <p>检索先按知识空间和完整模型描述过滤，再排除当前主体文档，最后按相似度和稳定标识排序。
 * 该实现是可解释的精确扫描，不承诺大规模 ANN 性能，也不接入默认文档关联候选链路。</p>
 */
public final class ExactCosineRetriever {

    /**
     * 按空间、模型版本和主体文档边界召回分片候选。
     *
     * @param spaceId 当前知识空间标识
     * @param excludedSourceDocumentId 当前主体资料标识；为空时不排除主体资料
     * @param chunkVersion 当前实验允许参与召回的分片版本
     * @param descriptor 查询向量的模型描述
     * @param query 查询向量
     * @param indexedVectors 已持久化或待重建的分片向量事实
     * @param topK 最大返回数量
     * @return 按相似度降序、标识升序稳定排列的候选
     */
    public List<SemanticChunkCandidate> retrieve(
            Long spaceId,
            Long excludedSourceDocumentId,
            String chunkVersion,
            EmbeddingModelDescriptor descriptor,
            EmbeddingVector query,
            List<SemanticChunkVector> indexedVectors,
            int topK
    ) {
        Objects.requireNonNull(spaceId, "检索空间标识不能为空");
        if (chunkVersion == null || chunkVersion.isBlank()) {
            throw new IllegalArgumentException("检索分片版本不能为空");
        }
        Objects.requireNonNull(descriptor, "检索模型描述不能为空");
        Objects.requireNonNull(query, "检索向量不能为空");
        Objects.requireNonNull(indexedVectors, "已索引向量列表不能为空");
        if (topK <= 0) {
            throw new IllegalArgumentException("TopK 必须大于 0");
        }
        if (query.dimension() != descriptor.dimension()) {
            throw new IllegalArgumentException("查询向量维度与模型描述不一致");
        }

        return indexedVectors.stream()
                .filter(item -> item.spaceId().equals(spaceId))
                .filter(item -> excludedSourceDocumentId == null
                        || !item.sourceDocumentId().equals(excludedSourceDocumentId))
                .filter(item -> item.chunkVersion().equals(chunkVersion))
                .filter(item -> item.descriptor().equals(descriptor))
                .map(item -> new SemanticChunkCandidate(
                        item.spaceId(),
                        item.sourceDocumentId(),
                        item.chunkRecordId(),
                        item.chunkId(),
                        item.contentHash(),
                        item.chunkVersion(),
                        CosineSimilarity.score(query, item.vector())
                ))
                .sorted(Comparator
                        .comparingDouble(SemanticChunkCandidate::score)
                        .reversed()
                        .thenComparing(SemanticChunkCandidate::sourceDocumentId)
                        .thenComparing(SemanticChunkCandidate::chunkRecordId))
                .limit(topK)
                .toList();
    }
}

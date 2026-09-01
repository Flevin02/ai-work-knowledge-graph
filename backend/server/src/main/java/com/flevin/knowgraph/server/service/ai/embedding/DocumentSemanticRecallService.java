package com.flevin.knowgraph.server.service.ai.embedding;

import com.flevin.knowgraph.server.model.ai.embedding.SemanticDocumentCandidate;
import com.flevin.knowgraph.server.model.ai.embedding.SemanticDocumentRecall;

import java.util.List;

/**
 * 独立文档语义召回服务，只服务阶段 3 Embedding 对照实验。
 *
 * <p>该服务以主体资料的章节感知分片向量为查询，在同一知识空间、同一分片版本和
 * 完整模型描述边界内执行精确 COSINE 召回，并将分片级结果聚合为文档级候选。
 * 结果不接入 {@code DocumentAssociationService} 的默认候选输入，正式关系仍必须
 * 经过关系判断、逐字证据校验和人工审核。</p>
 */
public interface DocumentSemanticRecallService {

    /** 独立语义召回策略版本；召回算法语义变化时必须显式升级。 */
    String SEMANTIC_RECALL_POLICY_VERSION = "document-semantic-recall-v1";

    /** 每个查询分片召回的分片级候选上限，与冻结候选评估保持一致的 TopK=8。 */
    int CHUNK_QUERY_TOP_K = 8;

    /** 文档级语义候选数量上限，与冻结候选评估保持一致的 TopK=8。 */
    int DOCUMENT_TOP_K = 8;

    /**
     * 按冻结的 TopK=8 规则召回当前文档的语义候选文档。
     *
     * @param spaceId 知识空间标识
     * @param sourceDocumentId 当前作为召回主体的来源资料标识
     * @return 文档级语义召回结果；主体没有可索引分片或空间内没有就绪向量时返回空候选
     */
    SemanticDocumentRecall recall(
            Long spaceId,
            Long sourceDocumentId
    );

    /**
     * 在召回结果上应用文档级分数下限，供接入开关使用。
     *
     * <p>过滤只影响返回的候选列表，不影响向量生成与索引状态；实验与评估调用
     * 继续使用无阈值的两参方法以保持结果可比。</p>
     *
     * @param spaceId 知识空间标识
     * @param sourceDocumentId 当前作为召回主体的来源资料标识
     * @param semanticMinScore 文档级 bestChunkScore 下限；不高于零时等价于无阈值召回
     * @return 过滤后重新赋秩的语义召回结果
     */
    default SemanticDocumentRecall recall(
            Long spaceId,
            Long sourceDocumentId,
            double semanticMinScore
    ) {
        SemanticDocumentRecall recall = recall(spaceId, sourceDocumentId);
        if (semanticMinScore <= 0) {
            return recall;
        }

        // 按分数下限过滤文档级候选，并按过滤后的顺序重新赋予 1-based 排名
        java.util.List<SemanticDocumentCandidate> filtered = recall.candidates().stream()
                .filter(candidate -> candidate.bestChunkScore() >= semanticMinScore)
                .toList();
        java.util.List<SemanticDocumentCandidate> ranked = new java.util.ArrayList<>(filtered.size());
        for (int index = 0; index < filtered.size(); index++) {
            SemanticDocumentCandidate candidate = filtered.get(index);
            ranked.add(new SemanticDocumentCandidate(
                    candidate.sourceDocumentId(),
                    candidate.bestChunkScore(),
                    candidate.bestChunkId(),
                    candidate.bestChunkRecordId(),
                    index + 1
            ));
        }
        return new SemanticDocumentRecall(
                recall.spaceId(),
                recall.sourceDocumentId(),
                recall.semanticRecallPolicyVersion(),
                recall.chunkVersion(),
                recall.descriptor(),
                recall.topK(),
                ranked,
                recall.queryChunkCount()
        );
    }
}

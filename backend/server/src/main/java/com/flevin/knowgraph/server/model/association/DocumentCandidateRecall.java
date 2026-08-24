package com.flevin.knowgraph.server.model.association;

import java.util.List;

/**
 * 一次无 Embedding 文档候选召回结果。
 *
 * @param spaceId 知识空间标识
 * @param sourceDocumentId 当前作为召回主体的来源资料标识
 * @param sourceContentHash 召回开始时主体文档内容指纹
 * @param candidateRecallPolicyVersion 候选召回策略版本
 * @param topK 本次召回上限
 * @param candidates 按规则分数和稳定文档标识排序的候选资料
 */
public record DocumentCandidateRecall(
        String spaceId,
        String sourceDocumentId,
        String sourceContentHash,
        String candidateRecallPolicyVersion,
        int topK,
        List<DocumentCandidate> candidates
) {

    public DocumentCandidateRecall {
        candidates = List.copyOf(candidates);
    }
}

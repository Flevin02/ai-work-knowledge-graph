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
 * @param sourceConfirmedTags 当前主体文档已确认标签
 * @param tagCandidateCount 仅由 confirmed 标签命中的候选数量
 * @param keywordCandidateCount 由默认内容通道命中的候选数量
 */
public record DocumentCandidateRecall(
        Long spaceId,
        Long sourceDocumentId,
        String sourceContentHash,
        String candidateRecallPolicyVersion,
        int topK,
        List<DocumentCandidate> candidates,
        List<String> sourceConfirmedTags,
        int tagCandidateCount,
        int keywordCandidateCount
) {

    public DocumentCandidateRecall {
        candidates = List.copyOf(candidates);
        sourceConfirmedTags = List.copyOf(sourceConfirmedTags);
    }
}

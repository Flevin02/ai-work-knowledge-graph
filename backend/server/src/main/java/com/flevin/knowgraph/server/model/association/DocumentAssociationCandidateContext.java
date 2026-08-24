package com.flevin.knowgraph.server.model.association;

import java.util.List;

/**
 * 提供给文档关联判断客户端的单个服务端候选。
 *
 * @param document 候选文档安全上下文
 * @param matchedChannels 候选召回命中通道
 * @param matchedTerms 候选召回命中的有限关键词
 * @param score 仅用于排序说明的确定性规则分数
 * @param rank 本次候选排名，从 1 开始
 */
public record DocumentAssociationCandidateContext(
        DocumentAssociationDocumentContext document,
        List<String> matchedChannels,
        List<String> matchedTerms,
        int score,
        int rank
) {

    public DocumentAssociationCandidateContext {
        matchedChannels = List.copyOf(matchedChannels);
        matchedTerms = List.copyOf(matchedTerms);
    }
}

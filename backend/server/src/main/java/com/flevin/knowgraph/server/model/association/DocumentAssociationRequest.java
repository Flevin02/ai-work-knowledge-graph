package com.flevin.knowgraph.server.model.association;

import java.util.List;

/**
 * 文档关联判断请求，限定模型只能在服务端候选集合内做关系选择。
 *
 * @param runId 文档关联运行标识
 * @param currentDocument 当前作为分析主体的文档上下文
 * @param candidates 最多 8 份服务端候选文档
 * @param promptVersion 文档关联 Prompt 版本
 * @param schemaVersion 文档关联输出 Schema 版本
 * @param associationPolicyVersion 文档关联策略版本
 */
public record DocumentAssociationRequest(
        Long runId,
        DocumentAssociationDocumentContext currentDocument,
        List<DocumentAssociationCandidateContext> candidates,
        String promptVersion,
        String schemaVersion,
        String associationPolicyVersion
) {

    public DocumentAssociationRequest {
        candidates = List.copyOf(candidates);
    }
}

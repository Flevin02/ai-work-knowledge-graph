package com.flevin.knowgraph.server.model.tag;

/**
 * 供应商无关的文档标签抽取请求。
 *
 * @param runId 标签运行标识
 * @param document 当前来源资料和可引用分片
 * @param promptVersion 标签 Prompt 版本
 * @param schemaVersion 标签输出 Schema 版本
 */
public record DocumentTaggingRequest(
        Long runId,
        DocumentTaggingDocumentContext document,
        String promptVersion,
        String schemaVersion
) {
}

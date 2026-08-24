package com.flevin.knowgraph.server.model.tag;

import java.time.Instant;

/**
 * 来源资料与标签之间的候选或确认关系。
 *
 * @param id 文档标签关系标识
 * @param spaceId 所属知识空间标识
 * @param sourceDocumentId 被标记的来源资料标识
 * @param tagId 空间内标签定义标识
 * @param sourceType 标签来源：ai、user 或 rule
 * @param status 文档标签状态：suggested、confirmed、rejected 或 stale
 * @param confidence AI 或规则置信度；用户手工标签为空
 * @param extractionRunId 可选标签抽取运行标识
 * @param contentHash 来源资料内容指纹快照
 * @param promptVersion AI 标签 Prompt 版本；用户手工标签为空
 * @param schemaVersion AI 标签 Schema 版本；用户手工标签为空
 * @param documentTagKey 稳定幂等键；可为空并由服务端计算
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间
 */
public record DocumentTag(
        String id,
        String spaceId,
        String sourceDocumentId,
        String tagId,
        String sourceType,
        String status,
        Double confidence,
        String extractionRunId,
        String contentHash,
        String promptVersion,
        String schemaVersion,
        String documentTagKey,
        Instant createdAt,
        Instant updatedAt
) {
}

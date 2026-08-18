package com.flevin.knowgraph.server.model.graph;

import java.time.Instant;

/**
 * 图谱关系证据持久化模型。
 *
 * @param id 证据标识
 * @param spaceId 所属知识空间标识
 * @param edgeId 所支撑关系标识
 * @param sourceDocumentId 来源资料标识
 * @param sourceDocumentName 来源资料名称
 * @param quote 原文证据片段
 * @param locator 原文定位
 * @param extractionMethod 提取方式
 * @param createdAt 创建时间
 */
public record GraphEvidence(
        String id,
        String spaceId,
        String edgeId,
        String sourceDocumentId,
        String sourceDocumentName,
        String quote,
        String locator,
        String extractionMethod,
        Instant createdAt
) {
}

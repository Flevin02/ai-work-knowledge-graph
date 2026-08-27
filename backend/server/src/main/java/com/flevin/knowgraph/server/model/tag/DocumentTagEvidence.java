package com.flevin.knowgraph.server.model.tag;

import java.time.Instant;

/**
 * AI 文档标签的可定位原文证据。
 *
 * @param id 标签证据标识
 * @param spaceId 所属知识空间标识
 * @param documentTagId 被证据支撑的文档标签关系标识
 * @param sourceDocumentId 证据所在来源资料标识
 * @param chunkId 当前资料内稳定的分片标识
 * @param sectionPath 分片所属章节路径
 * @param quote 可逐字反查的原文片段
 * @param startOffset 原文起始偏移
 * @param endOffset 原文结束偏移，不包含该位置字符
 * @param createdAt 创建时间
 */
public record DocumentTagEvidence(
        Long id,
        Long spaceId,
        Long documentTagId,
        Long sourceDocumentId,
        String chunkId,
        String sectionPath,
        String quote,
        Integer startOffset,
        Integer endOffset,
        Instant createdAt
) {
}

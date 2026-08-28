package com.flevin.knowgraph.server.model.ai.rag;

import java.time.Instant;

/**
 * 可重建持久化的来源资料分片事实。
 *
 * @param id Snowflake 分片事实标识
 * @param spaceId 知识空间标识
 * @param sourceDocumentId 来源资料标识
 * @param sectionRecordId 所属章节事实标识
 * @param sectionId 所属章节标识
 * @param chunkId 文档内稳定分片标识
 * @param parserVersion 章节解析规则版本
 * @param sectionPath 章节路径
 * @param ordinal 章节内分片顺序
 * @param documentOrdinal 来源资料内分片全局顺序
 * @param contentText 分片原文
 * @param startOffset 原文起始偏移
 * @param endOffset 原文结束偏移
 * @param contentHash 分片内容指纹
 * @param chunkVersion 分片策略版本
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record DocumentChunkFact(
        Long id,
        Long spaceId,
        Long sourceDocumentId,
        Long sectionRecordId,
        String sectionId,
        String chunkId,
        String parserVersion,
        String sectionPath,
        int ordinal,
        int documentOrdinal,
        String contentText,
        int startOffset,
        int endOffset,
        String contentHash,
        String chunkVersion,
        Instant createdAt,
        Instant updatedAt
) {
}

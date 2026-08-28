package com.flevin.knowgraph.server.model.ai.rag;

import java.time.Instant;

/**
 * 可重建持久化的来源资料章节事实。
 *
 * @param id Snowflake 章节事实标识
 * @param spaceId 知识空间标识
 * @param sourceDocumentId 来源资料标识
 * @param sectionId 文档内稳定章节标识
 * @param parserVersion 章节解析规则版本
 * @param title 章节标题
 * @param level 标题层级
 * @param sectionPath 章节路径
 * @param ordinal 文档内章节顺序
 * @param contentText 章节原文
 * @param startOffset 原文起始偏移
 * @param endOffset 原文结束偏移
 * @param contentHash 章节内容指纹
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record DocumentSectionFact(
        Long id,
        Long spaceId,
        Long sourceDocumentId,
        String sectionId,
        String parserVersion,
        String title,
        int level,
        String sectionPath,
        int ordinal,
        String contentText,
        int startOffset,
        int endOffset,
        String contentHash,
        Instant createdAt,
        Instant updatedAt
) {
}

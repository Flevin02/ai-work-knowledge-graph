package com.flevin.knowgraph.server.model.ai.rag;

import java.util.List;

/**
 * 已写入 MySQL 的当前文档结构事实快照。
 *
 * @param parserVersion 章节解析规则版本
 * @param chunkVersion 包含解析规则、分片策略和关键参数的完整分片版本
 * @param sections 按来源原文顺序排列的章节事实
 * @param chunks 按来源原文顺序排列的分片事实
 */
public record PersistedDocumentStructure(
        String parserVersion,
        String chunkVersion,
        List<DocumentSectionFact> sections,
        List<DocumentChunkFact> chunks
) {

    public PersistedDocumentStructure {
        // 固化返回集合，避免调用方改变已经持久化的事实顺序
        sections = List.copyOf(sections);
        // 固化返回集合，避免调用方改变已经持久化的事实顺序
        chunks = List.copyOf(chunks);
    }
}

package com.flevin.knowgraph.server.model.ai.rag;

/**
 * 用于 Embedding、检索和证据定位的章节感知文本分片。
 *
 * @param chunkId 当前文档内稳定的分片标识
 * @param sectionId 所属章节标识
 * @param sectionPath 所属章节路径
 * @param ordinal 所属章节内分片顺序
 * @param contentText 可直接反查到来源文档的原文
 * @param startOffset 分片在来源文档中的起始偏移
 * @param endOffset 分片在来源文档中的结束偏移，不包含该位置字符
 */
public record DocumentChunk(
        String chunkId,
        String sectionId,
        String sectionPath,
        int ordinal,
        String contentText,
        int startOffset,
        int endOffset
) {
}

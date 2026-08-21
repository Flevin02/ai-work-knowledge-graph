package com.flevin.knowgraph.server.model.ai;

import java.util.List;

/**
 * 文档级全文摘要请求，输入已经通过结构和证据校验的分片摘要。
 *
 * @param sourceDocumentId 来源资料标识
 * @param documentName 来源资料名称
 * @param documentType 来源资料业务类型
 * @param chunks 按原文顺序排列的分片摘要
 */
public record AiDocumentSummaryRequest(
        String sourceDocumentId,
        String documentName,
        String documentType,
        List<ChunkSummary> chunks
) {

    /**
     * 文档级汇总使用的单个分片摘要素材。
     *
     * @param chunkId 分片标识
     * @param sectionPath 章节路径
     * @param summary 已通过分片抽取校验的摘要
     */
    public record ChunkSummary(
            String chunkId,
            String sectionPath,
            String summary
    ) {
    }
}

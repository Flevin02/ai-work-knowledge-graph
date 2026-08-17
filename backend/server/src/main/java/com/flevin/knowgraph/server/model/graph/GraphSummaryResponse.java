package com.flevin.knowgraph.server.model.graph;

/**
 * 知识图谱摘要响应。
 *
 * @param nodes 节点数量
 * @param edges 关系数量
 * @param pendingReviews 待审核关系数量
 * @param message 当前状态说明
 */
public record GraphSummaryResponse(
        int nodes,
        int edges,
        int pendingReviews,
        String message
) {
}

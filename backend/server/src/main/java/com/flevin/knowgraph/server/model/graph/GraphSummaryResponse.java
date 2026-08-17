package com.flevin.knowgraph.server.model.graph;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 知识图谱摘要响应。
 *
 * @param nodes 节点数量
 * @param edges 关系数量
 * @param pendingReviews 待审核关系数量
 * @param message 当前状态说明
 */
@Schema(name = "GraphSummaryResponse", description = "知识图谱节点、关系和审核数量摘要")
public record GraphSummaryResponse(
        @Schema(description = "当前图谱节点数量", example = "14")
        int nodes,
        @Schema(description = "当前图谱关系数量", example = "11")
        int edges,
        @Schema(description = "待人工审核的关系数量", example = "1")
        int pendingReviews,
        @Schema(description = "当前图谱状态说明", example = "图谱已加载")
        String message
) {
}

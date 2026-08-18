package com.flevin.knowgraph.server.model.graph;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 指定知识空间的图谱基础查询响应。
 *
 * @param nodes 图谱节点
 * @param edges 带证据的图谱关系
 */
@Schema(description = "知识空间的图谱节点、关系和证据")
public record GraphDataResponse(
        @Schema(description = "图谱节点")
        List<GraphNodeResponse> nodes,
        @Schema(description = "带证据的图谱关系")
        List<GraphEdgeResponse> edges
) {
}

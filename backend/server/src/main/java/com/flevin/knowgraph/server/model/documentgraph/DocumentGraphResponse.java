package com.flevin.knowgraph.server.model.documentgraph;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 当前知识空间的独立文档关系图数据。
 *
 * @param nodes 有效来源文档节点
 * @param edges 已确认文档关系边
 */
@Schema(description = "独立文档关系图数据")
public record DocumentGraphResponse(
        @Schema(description = "真实来源文档节点") List<DocumentGraphNodeResponse> nodes,
        @Schema(description = "已确认文档关系边") List<DocumentGraphEdgeResponse> edges
) {

    public DocumentGraphResponse {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
    }
}

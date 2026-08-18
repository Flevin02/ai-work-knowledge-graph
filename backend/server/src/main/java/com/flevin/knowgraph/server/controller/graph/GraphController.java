package com.flevin.knowgraph.server.controller.graph;

import com.flevin.knowgraph.common.model.ApiResponse;
import com.flevin.knowgraph.server.model.graph.GraphDataResponse;
import com.flevin.knowgraph.server.model.graph.GraphSummaryResponse;
import com.flevin.knowgraph.server.service.graph.GraphService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/v1/graph")
@Tag(name = "知识图谱", description = "查询当前知识图谱的节点、关系和审核状态")
public class GraphController {

	private final GraphService graphService;

	public GraphController(GraphService graphService) {
		this.graphService = graphService;
	}

    @GetMapping(value = "/summary", name = "查询知识图谱摘要")
    @Operation(summary = "查询知识图谱摘要", description = "返回当前知识图谱节点数、关系数和待审核关系数。")
    public ApiResponse<GraphSummaryResponse> summary(
            @Parameter(description = "知识空间标识", example = "default-space")
            @RequestParam String spaceId
    ) {
        // 获取指定知识空间图谱节点、关系和待审核数量
        GraphSummaryResponse response = graphService.getSummary(spaceId);

		// 使用脚手架统一响应结构返回图谱摘要
		return ApiResponse.success(response);
	}

    @GetMapping(value = "", name = "查询知识图谱数据")
    @Operation(summary = "查询知识图谱数据", description = "返回指定知识空间的节点、关系及对应来源证据。")
    public ApiResponse<GraphDataResponse> graph(
            @Parameter(description = "知识空间标识", example = "default-space")
            @RequestParam String spaceId
    ) {
        // 查询指定知识空间的图谱节点、关系和证据
        GraphDataResponse response = graphService.getGraph(spaceId);

        // 使用统一响应结构返回图谱数据
        return ApiResponse.success(response);
    }
}

package com.flevin.knowgraph.server.controller.graph;

import com.flevin.knowgraph.common.model.ApiResponse;
import com.flevin.knowgraph.server.model.graph.GraphSummaryResponse;
import com.flevin.knowgraph.server.service.graph.GraphService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/graph")
public class GraphController {

    private final GraphService graphService;

    public GraphController(GraphService graphService) {
        this.graphService = graphService;
    }

    @GetMapping(value = "/summary", name = "查询知识图谱摘要")
    public ApiResponse<GraphSummaryResponse> summary() {
        // 获取当前图谱节点、关系和待审核数量
        GraphSummaryResponse response = graphService.getSummary();

        // 使用脚手架统一响应结构返回图谱摘要
        return ApiResponse.success(response);
    }
}

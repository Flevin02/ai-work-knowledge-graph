package com.flevin.knowgraph.server.controller.document;

import com.flevin.knowgraph.common.model.ApiResponse;
import com.flevin.knowgraph.server.model.documentgraph.DocumentGraphResponse;
import com.flevin.knowgraph.server.service.documentgraph.DocumentGraphService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 独立文档关系图查询接口。
 */
@RestController
@RequestMapping("/v1/spaces/{spaceId}/document-graph")
@Tag(name = "文档关系图", description = "查询真实来源文档和已确认文档关系")
@RequiredArgsConstructor
public class DocumentGraphController {

    private final DocumentGraphService documentGraphService;

    @GetMapping(value = "", name = "查询文档关系图")
    @Operation(
            summary = "查询文档关系图",
            description = "返回当前知识空间的真实来源文档节点和已确认文档关系边；提供 tagId 时只返回含该 confirmed 标签的文档节点与关系。"
    )
    public ApiResponse<DocumentGraphResponse> getGraph(
            @Parameter(description = "知识空间标识") @PathVariable Long spaceId,
            @Parameter(description = "可选的 confirmed 标签定义标识，用于按标签筛选图谱")
            @RequestParam(required = false) Long tagId
    ) {
        // 查询独立文档关系图，避免与历史实体图谱混合
        DocumentGraphResponse response = documentGraphService.getGraph(spaceId, tagId);

        // 使用统一响应结构返回文档关系图
        return ApiResponse.success(response);
    }
}

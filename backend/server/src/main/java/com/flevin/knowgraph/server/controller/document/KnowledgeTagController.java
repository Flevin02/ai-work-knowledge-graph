package com.flevin.knowgraph.server.controller.document;

import com.flevin.knowgraph.common.model.ApiResponse;
import com.flevin.knowgraph.server.model.tag.KnowledgeTagSummaryResponse;
import com.flevin.knowgraph.server.service.tag.DocumentTagService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1/spaces/{spaceId}/tags")
@Tag(name = "文档标签", description = "查询当前知识空间可用于导航和筛选的已确认标签")
@RequiredArgsConstructor
public class KnowledgeTagController {

    private final DocumentTagService documentTagService;

    @GetMapping(value = "", name = "查询知识空间已确认标签")
    @Operation(
            summary = "查询知识空间已确认标签",
            description = "只返回至少关联一份有效来源资料的 confirmed 标签及文档数量，不暴露 suggested 或 rejected 候选。"
    )
    public ApiResponse<List<KnowledgeTagSummaryResponse>> listConfirmedTags(
            @Parameter(description = "知识空间标识") @PathVariable Long spaceId
    ) {
        // 聚合当前空间 confirmed 标签及其有效来源资料数量
        List<KnowledgeTagSummaryResponse> response = documentTagService.listConfirmedTags(spaceId);

        // 使用统一响应返回桌面 Web 标签导航所需摘要
        return ApiResponse.success(response);
    }
}

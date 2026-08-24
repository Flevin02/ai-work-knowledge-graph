package com.flevin.knowgraph.server.controller.document;

import com.flevin.knowgraph.common.model.ApiResponse;
import com.flevin.knowgraph.server.model.tag.DocumentTaggingRunResponse;
import com.flevin.knowgraph.server.service.tag.DocumentTaggingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/spaces/{spaceId}/documents/{documentId}")
@Tag(name = "文档标签", description = "基于逐字证据生成和恢复待审核的来源资料标签")
@RequiredArgsConstructor
public class DocumentTaggingController {

    private final DocumentTaggingService documentTaggingService;

    @PostMapping(value = "/tagging-runs", name = "创建文档标签运行")
    @Operation(
            summary = "创建文档标签运行",
            description = "执行 document-tag-v1 标签抽取和服务端三层校验，只有有效候选保存为 suggested。"
    )
    public ApiResponse<DocumentTaggingRunResponse> createRun(
            @Parameter(description = "知识空间标识") @PathVariable String spaceId,
            @Parameter(description = "当前分析的来源资料标识") @PathVariable String documentId
    ) {
        // 为当前来源资料执行一次可恢复的固定标签 Pipeline
        DocumentTaggingRunResponse response = documentTaggingService.createRun(
                spaceId,
                documentId
        );

        // 使用统一响应返回运行状态、失败阶段和已校验标签建议
        return ApiResponse.success(response);
    }

    @GetMapping(value = "/tagging-runs/{runId}", name = "查询文档标签运行")
    @Operation(
            summary = "查询文档标签运行",
            description = "按空间、来源资料和运行标识恢复状态、版本、摘要和本次新保存的标签建议。"
    )
    public ApiResponse<DocumentTaggingRunResponse> getRun(
            @Parameter(description = "知识空间标识") @PathVariable String spaceId,
            @Parameter(description = "当前分析的来源资料标识") @PathVariable String documentId,
            @Parameter(description = "文档标签运行标识") @PathVariable String runId
    ) {
        // 按空间、来源资料和运行标识恢复标签运行结果
        DocumentTaggingRunResponse response = documentTaggingService.getRun(
                spaceId,
                documentId,
                runId
        );

        // 使用统一响应返回可重复恢复的运行详情
        return ApiResponse.success(response);
    }
}

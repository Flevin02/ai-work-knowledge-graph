package com.flevin.knowgraph.server.controller.document;

import com.flevin.knowgraph.common.model.ApiResponse;
import com.flevin.knowgraph.server.model.association.DocumentAssociationRunResponse;
import com.flevin.knowgraph.server.model.association.DocumentRelationResponse;
import com.flevin.knowgraph.server.model.association.DocumentRelationReviewBatchRequest;
import com.flevin.knowgraph.server.model.association.DocumentRelationReviewBatchResponse;
import com.flevin.knowgraph.server.service.association.DocumentAssociationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@RestController
@RequestMapping("/v1/spaces/{spaceId}/documents/{documentId}")
@Tag(name = "文档关联", description = "基于内容候选、逐字证据和人工审核维护真实来源文档关系")
@RequiredArgsConstructor
public class DocumentAssociationController {

    private final DocumentAssociationService documentAssociationService;

    @PostMapping(value = "/association-runs", name = "创建文档关联运行")
    @Operation(
            summary = "创建文档关联运行",
            description = "执行无 Embedding 候选召回、关联判断和服务端证据校验；只有校验通过的非 none 结果保存为 suggested。"
    )
    public ApiResponse<DocumentAssociationRunResponse> createRun(
            @Parameter(description = "知识空间标识") @PathVariable Long spaceId,
            @Parameter(description = "当前分析的来源资料标识") @PathVariable Long documentId,
            @Parameter(description = "是否显式开启已确认标签补充候选，默认关闭")
            @RequestParam(defaultValue = "false") boolean includeConfirmedTags
    ) {
        // 为当前来源资料执行一次可恢复的固定文档关联 Pipeline
        DocumentAssociationRunResponse response = documentAssociationService.createRun(
                spaceId,
                documentId,
                includeConfirmedTags
        );

        // 使用统一响应返回运行状态、失败阶段和已校验建议
        return ApiResponse.success(response);
    }

    @GetMapping(value = "/association-runs/{runId}", name = "查询文档关联运行")
    @Operation(
            summary = "查询文档关联运行",
            description = "恢复指定运行的状态、版本、统计和本次新保存的文档关系建议。"
    )
    public ApiResponse<DocumentAssociationRunResponse> getRun(
            @Parameter(description = "知识空间标识") @PathVariable Long spaceId,
            @Parameter(description = "当前分析的来源资料标识") @PathVariable Long documentId,
            @Parameter(description = "文档关联运行标识") @PathVariable Long runId
    ) {
        // 按空间、来源资料和运行标识恢复文档关联结果
        DocumentAssociationRunResponse response = documentAssociationService.getRun(
                spaceId,
                documentId,
                runId
        );

        // 使用统一响应返回可重复恢复的运行详情
        return ApiResponse.success(response);
    }

    @GetMapping(value = "/relations", name = "查询来源资料文档关系")
    @Operation(
            summary = "查询来源资料文档关系",
            description = "返回当前资料作为任一端点的 suggested、confirmed、rejected 或 stale 关系及已校验证据。"
    )
    public ApiResponse<List<DocumentRelationResponse>> listRelations(
            @Parameter(description = "知识空间标识") @PathVariable Long spaceId,
            @Parameter(description = "来源资料标识") @PathVariable Long documentId
    ) {
        // 查询当前资料作为主体或客体的全部文档关系
        List<DocumentRelationResponse> response = documentAssociationService.listRelations(
                spaceId,
                documentId
        );

        // 使用统一响应返回关系及其逐字证据
        return ApiResponse.success(response);
    }

    @PostMapping(value = "/relation-review-batches", name = "批量审核文档关系")
    @Operation(
            summary = "批量审核文档关系",
            description = "只按服务端关系标识批量采纳或拒绝 suggested 建议，不接受客户端覆盖关系内容、置信度或证据。"
    )
    public ApiResponse<DocumentRelationReviewBatchResponse> reviewRelations(
            @Parameter(description = "知识空间标识") @PathVariable Long spaceId,
            @Parameter(description = "来源资料标识") @PathVariable Long documentId,
            @Valid @RequestBody DocumentRelationReviewBatchRequest request
    ) {
        // 按服务端关系标识执行 suggested 到 confirmed/rejected 的状态迁移
        DocumentRelationReviewBatchResponse response = documentAssociationService.reviewRelations(
                spaceId,
                documentId,
                request
        );

        // 使用统一响应返回审核统计和最新关系状态
        return ApiResponse.success(response);
    }
}

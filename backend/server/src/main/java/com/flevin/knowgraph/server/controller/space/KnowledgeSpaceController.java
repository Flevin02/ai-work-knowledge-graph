package com.flevin.knowgraph.server.controller.space;

import com.flevin.knowgraph.common.model.ApiResponse;
import com.flevin.knowgraph.server.model.space.CreateKnowledgeSpaceRequest;
import com.flevin.knowgraph.server.model.space.KnowledgeSpaceResponse;
import com.flevin.knowgraph.server.service.space.KnowledgeSpaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1/spaces")
@Tag(name = "知识空间", description = "创建、切换和软删除相互隔离的本地知识空间")
public class KnowledgeSpaceController {

    private final KnowledgeSpaceService knowledgeSpaceService;

    public KnowledgeSpaceController(KnowledgeSpaceService knowledgeSpaceService) {
        this.knowledgeSpaceService = knowledgeSpaceService;
    }

    @GetMapping(value = "", name = "查询知识空间列表")
    @Operation(summary = "查询知识空间列表", description = "返回当前全部有效知识空间。")
    public ApiResponse<List<KnowledgeSpaceResponse>> listSpaces() {
        // 查询当前有效知识空间
        List<KnowledgeSpaceResponse> spaces = knowledgeSpaceService.listSpaces();

        // 使用统一响应结构返回空间列表
        return ApiResponse.success(spaces);
    }

    @PostMapping(value = "", name = "创建知识空间")
    @Operation(summary = "创建知识空间", description = "创建本地知识空间并准备独立来源资料目录。")
    public ApiResponse<KnowledgeSpaceResponse> createSpace(
            @Valid @RequestBody CreateKnowledgeSpaceRequest request
    ) {
        // 创建知识空间和对应本地目录
        KnowledgeSpaceResponse response = knowledgeSpaceService.createSpace(request);

        // 使用统一响应结构返回新知识空间
        return ApiResponse.success(response);
    }

    @DeleteMapping(value = "/{spaceId}", name = "删除知识空间")
    @Operation(summary = "删除知识空间", description = "软删除知识空间并保留其来源资料和图谱事实。")
    public ApiResponse<Void> deleteSpace(@PathVariable String spaceId) {
        // 软删除指定知识空间并保留事实来源
        knowledgeSpaceService.deleteSpace(spaceId);

        // 返回统一成功响应
        return ApiResponse.success(null);
    }
}

package com.flevin.knowgraph.server.model.space;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建知识空间请求。
 *
 * @param name 知识空间名称
 * @param description 用途说明
 */
@Schema(description = "创建知识空间请求")
public record CreateKnowledgeSpaceRequest(
        @NotBlank(message = "知识空间名称不能为空")
        @Size(max = 40, message = "知识空间名称不能超过 40 个字符")
        @Schema(description = "知识空间名称", example = "新产品发布会")
        String name,
        @Size(max = 200, message = "知识空间说明不能超过 200 个字符")
        @Schema(description = "知识空间用途说明", example = "整理发布会方案、供应商和执行任务。")
        String description
) {
}

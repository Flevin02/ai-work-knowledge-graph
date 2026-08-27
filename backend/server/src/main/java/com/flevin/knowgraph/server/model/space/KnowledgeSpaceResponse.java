package com.flevin.knowgraph.server.model.space;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 知识空间接口响应。
 *
 * @param id 知识空间标识
 * @param name 知识空间名称
 * @param description 用途说明
 * @param status 空间状态
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间
 */
@Schema(description = "用于隔离来源资料和图谱数据的知识空间")
public record KnowledgeSpaceResponse(
        @Schema(description = "知识空间标识", example = "e5d7b0da-60bd-4e0c-83df-5e7de9509327")
        Long id,
        @Schema(description = "知识空间名称", example = "产品需求资料")
        String name,
        @Schema(description = "知识空间用途说明", example = "用于整理年会方案、会议纪要和任务分工。")
        String description,
        @Schema(description = "知识空间状态", example = "active")
        String status,
        @Schema(description = "创建时间")
        Instant createdAt,
        @Schema(description = "最近更新时间")
        Instant updatedAt
) {
}

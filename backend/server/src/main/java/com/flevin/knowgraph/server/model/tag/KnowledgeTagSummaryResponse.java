package com.flevin.knowgraph.server.model.tag;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 当前知识空间内可用于筛选的已确认标签摘要。
 *
 * @param tagId 标签定义标识
 * @param name 标签展示名称
 * @param normalizedKey 标签规范化键
 * @param documentCount 当前有效文档数量
 * @param updatedAt 最近一次文档标签确认时间
 */
@Schema(description = "知识空间已确认标签摘要")
public record KnowledgeTagSummaryResponse(
        @Schema(description = "标签定义标识") Long tagId,
        @Schema(description = "标签展示名称", example = "年会筹备") String name,
        @Schema(description = "标签规范化键", example = "年会筹备") String normalizedKey,
        @Schema(description = "当前有效文档数量", example = "3") long documentCount,
        @Schema(description = "最近一次文档标签确认时间") Instant updatedAt
) {
}

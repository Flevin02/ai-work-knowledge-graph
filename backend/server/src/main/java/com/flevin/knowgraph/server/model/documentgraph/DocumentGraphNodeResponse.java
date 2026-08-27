package com.flevin.knowgraph.server.model.documentgraph;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 文档关系图中的真实来源文档节点。
 *
 * @param id 来源资料标识
 * @param name 原始文件名
 * @param kind 文件格式
 * @param documentType 文档业务类型
 * @param summary 文档摘要或导入摘要回退文本
 * @param status 来源资料状态
 * @param updatedAt 最近更新时间
 */
@Schema(description = "文档关系图中的来源文档节点")
public record DocumentGraphNodeResponse(
        @Schema(description = "来源资料标识") Long id,
        @Schema(description = "原始文件名") String name,
        @Schema(description = "文件格式", example = "markdown") String kind,
        @Schema(description = "文档业务类型", example = "general") String documentType,
        @Schema(description = "文档摘要") String summary,
        @Schema(description = "来源资料状态", example = "active") String status,
        @Schema(description = "最近更新时间") Instant updatedAt
) {
}

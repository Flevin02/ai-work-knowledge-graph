package com.flevin.knowgraph.server.model.documentgraph;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 文档关系图中的已确认文档关系边。
 *
 * @param id 文档关系标识
 * @param sourceDocumentId 关系主体文档标识
 * @param targetDocumentId 关系客体文档标识
 * @param relationType 关系类型
 * @param direction 关系方向
 * @param status 审核状态；当前图默认只返回 confirmed
 * @param confidence 关系置信度
 * @param reason 关系判断原因
 * @param updatedAt 最近更新时间
 */
@Schema(description = "文档关系图中的文档关系边")
public record DocumentGraphEdgeResponse(
        @Schema(description = "文档关系标识") String id,
        @Schema(description = "关系主体文档标识") String sourceDocumentId,
        @Schema(description = "关系客体文档标识") String targetDocumentId,
        @Schema(description = "关系类型", example = "references") String relationType,
        @Schema(description = "关系方向", example = "current_to_candidate") String direction,
        @Schema(description = "审核状态", example = "confirmed") String status,
        @Schema(description = "关系置信度", example = "0.91") double confidence,
        @Schema(description = "关系判断原因") String reason,
        @Schema(description = "最近更新时间") Instant updatedAt
) {
}

package com.flevin.knowgraph.server.model.graph;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * 图谱节点接口响应。
 *
 * @param id 节点标识
 * @param type 节点类型
 * @param label 展示名称
 * @param summary 节点摘要
 * @param status 节点状态
 * @param sourceIds 来源资料标识
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间
 */
@Schema(description = "知识图谱节点")
public record GraphNodeResponse(
        @Schema(description = "节点标识")
        String id,
        @Schema(description = "节点类型", example = "project")
        String type,
        @Schema(description = "节点名称", example = "2026 年公司年会")
        String label,
        @Schema(description = "节点摘要")
        String summary,
        @Schema(description = "节点状态", example = "active")
        String status,
        @Schema(description = "支撑节点的来源资料标识")
        List<String> sourceIds,
        @Schema(description = "创建时间")
        Instant createdAt,
        @Schema(description = "最近更新时间")
        Instant updatedAt
) {
}

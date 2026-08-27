package com.flevin.knowgraph.server.model.graph;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * 图谱关系接口响应。
 *
 * @param id 关系标识
 * @param source 主体节点标识
 * @param target 客体节点标识
 * @param type 关系类型
 * @param status 关系状态
 * @param confidence 置信度
 * @param evidence 关系证据
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间
 */
@Schema(description = "带来源证据的知识图谱关系")
public record GraphEdgeResponse(
        @Schema(description = "关系标识")
        Long id,
        @Schema(description = "主体节点标识")
        String source,
        @Schema(description = "客体节点标识")
        String target,
        @Schema(description = "关系类型", example = "项目负责人")
        String type,
        @Schema(description = "关系状态", example = "suggested")
        String status,
        @Schema(description = "置信度", example = "0.96")
        double confidence,
        @Schema(description = "关系证据")
        List<GraphEvidenceResponse> evidence,
        @Schema(description = "创建时间")
        Instant createdAt,
        @Schema(description = "最近更新时间")
        Instant updatedAt
) {
}

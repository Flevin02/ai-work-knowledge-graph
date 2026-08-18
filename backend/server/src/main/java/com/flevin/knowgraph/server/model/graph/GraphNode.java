package com.flevin.knowgraph.server.model.graph;

import java.time.Instant;
import java.util.List;

/**
 * 图谱节点持久化模型。
 *
 * @param id 节点标识
 * @param spaceId 所属知识空间标识
 * @param type 节点类型
 * @param label 展示名称
 * @param summary 节点摘要
 * @param status 节点状态
 * @param normalizedKey 实体规范化键
 * @param sourceIds 支撑节点的来源资料标识
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间
 */
public record GraphNode(
        String id,
        String spaceId,
        String type,
        String label,
        String summary,
        String status,
        String normalizedKey,
        List<String> sourceIds,
        Instant createdAt,
        Instant updatedAt
) {
}

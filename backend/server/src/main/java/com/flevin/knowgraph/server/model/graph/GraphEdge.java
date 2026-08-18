package com.flevin.knowgraph.server.model.graph;

import java.time.Instant;

/**
 * 图谱关系持久化模型。
 *
 * @param id 关系标识
 * @param spaceId 所属知识空间标识
 * @param sourceNodeId 主体节点标识
 * @param targetNodeId 客体节点标识
 * @param type 关系类型
 * @param status 关系状态
 * @param confidence 置信度
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间
 */
public record GraphEdge(
        String id,
        String spaceId,
        String sourceNodeId,
        String targetNodeId,
        String type,
        String status,
        double confidence,
        Instant createdAt,
        Instant updatedAt
) {
}

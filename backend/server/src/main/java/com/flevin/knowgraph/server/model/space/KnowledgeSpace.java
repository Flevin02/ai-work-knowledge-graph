package com.flevin.knowgraph.server.model.space;

import java.time.Instant;

/**
 * 知识空间持久化模型。
 *
 * @param id 知识空间标识
 * @param name 知识空间名称
 * @param description 用途说明
 * @param status 空间状态
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间
 */
public record KnowledgeSpace(
        Long id,
        String name,
        String description,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
}

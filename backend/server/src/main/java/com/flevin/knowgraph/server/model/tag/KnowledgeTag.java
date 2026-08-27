package com.flevin.knowgraph.server.model.tag;

import java.time.Instant;

/**
 * 知识空间内可复用的标签定义。
 *
 * @param id 标签标识
 * @param spaceId 所属知识空间标识
 * @param name 面向用户展示的标签名称
 * @param normalizedKey 标签轻量规范化键
 * @param status 标签字典状态：active 或 inactive
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间
 */
public record KnowledgeTag(
        Long id,
        Long spaceId,
        String name,
        String normalizedKey,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
}

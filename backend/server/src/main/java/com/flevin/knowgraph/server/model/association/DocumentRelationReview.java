package com.flevin.knowgraph.server.model.association;

import java.time.Instant;

/**
 * 文档关系审核历史领域模型。
 *
 * @param id 审核记录标识
 * @param spaceId 所属知识空间标识
 * @param documentRelationId 被审核的文档关系标识
 * @param action 审核动作：accept、reject 或 create
 * @param reason 审核说明
 * @param operatorName 操作者展示名称
 * @param createdAt 审核时间
 */
public record DocumentRelationReview(
        Long id,
        Long spaceId,
        Long documentRelationId,
        String action,
        String reason,
        String operatorName,
        Instant createdAt
) {
}

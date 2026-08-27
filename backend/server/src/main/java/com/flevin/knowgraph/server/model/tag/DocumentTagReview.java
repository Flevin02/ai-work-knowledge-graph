package com.flevin.knowgraph.server.model.tag;

import java.time.Instant;

/**
 * 文档标签不可变审核历史。
 *
 * @param id 审核记录标识
 * @param spaceId 所属知识空间标识
 * @param documentTagId 被审核的文档标签关系标识
 * @param action 审核动作：accept 或 reject
 * @param reason 可选审核说明
 * @param operatorName 操作者展示名称
 * @param createdAt 审核时间
 */
public record DocumentTagReview(
        Long id,
        Long spaceId,
        Long documentTagId,
        String action,
        String reason,
        String operatorName,
        Instant createdAt
) {
}

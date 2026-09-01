package com.flevin.knowgraph.server.model.conversation;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 问答会话元数据。
 *
 * @param conversationId 会话标识
 * @param spaceId 所属知识空间标识
 * @param title 会话标题
 * @param scopeDocumentId 可选当前文档范围标识
 * @param status 会话状态
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间
 */
@Schema(description = "问答会话元数据")
public record ConversationResponse(
        @Schema(description = "会话标识") Long conversationId,
        @Schema(description = "所属知识空间标识") Long spaceId,
        @Schema(description = "会话标题", example = "年会方案答疑") String title,
        @Schema(description = "可选当前文档范围标识") Long scopeDocumentId,
        @Schema(description = "会话状态", example = "active") String status,
        @Schema(description = "创建时间") Instant createdAt,
        @Schema(description = "最近更新时间") Instant updatedAt
) {
}

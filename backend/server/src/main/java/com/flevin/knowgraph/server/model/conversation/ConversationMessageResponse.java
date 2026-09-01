package com.flevin.knowgraph.server.model.conversation;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * 问答会话消息及回答状态。
 *
 * @param messageId 消息标识
 * @param conversationId 所属会话标识
 * @param role 消息角色：user 或 assistant
 * @param content 问题或最终回答正文
 * @param status 消息状态：completed 或 failed
 * @param groundingStatus 回答证据状态：grounded、partially_grounded 或 insufficient_evidence
 * @param errorCategory 失败错误类别
 * @param errorMessage 面向用户的稳定错误摘要
 * @param answerClient 生成回答的客户端实现标识
 * @param promptVersion 回答生成 Prompt 版本快照
 * @param schemaVersion 回答结构 Schema 版本快照
 * @param citationCount 通过逐字反查并保留的引用数量
 * @param citationFailureCount 因反查失败被移除的引用数量
 * @param durationMs 回答生成耗时毫秒
 * @param createdAt 创建时间
 * @param citations 通过校验并保留的引用列表；用户消息为空列表
 */
@Schema(description = "问答会话消息")
public record ConversationMessageResponse(
        @Schema(description = "消息标识") Long messageId,
        @Schema(description = "所属会话标识") Long conversationId,
        @Schema(description = "消息角色", example = "assistant") String role,
        @Schema(description = "问题或回答正文") String content,
        @Schema(description = "消息状态", example = "completed") String status,
        @Schema(description = "回答证据状态", example = "grounded") String groundingStatus,
        @Schema(description = "失败错误类别", example = "answer_client_unavailable") String errorCategory,
        @Schema(description = "面向用户的稳定错误摘要") String errorMessage,
        @Schema(description = "生成回答的客户端实现标识", example = "fake") String answerClient,
        @Schema(description = "回答 Prompt 版本", example = "conversation-answer-v1") String promptVersion,
        @Schema(description = "回答 Schema 版本", example = "conversation-answer-schema-v1") String schemaVersion,
        @Schema(description = "保留的引用数量", example = "2") int citationCount,
        @Schema(description = "反查失败被移除的引用数量", example = "0") int citationFailureCount,
        @Schema(description = "回答生成耗时毫秒", example = "1200") Long durationMs,
        @Schema(description = "创建时间") Instant createdAt,
        @Schema(description = "通过校验的引用列表") List<MessageCitationResponse> citations
) {

    public ConversationMessageResponse {
        // 引用列表统一不可变，避免上层修改响应内容
        citations = List.copyOf(citations);
    }
}

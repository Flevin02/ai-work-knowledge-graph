package com.flevin.knowgraph.server.model.conversation;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 问答会话详情，包含会话元数据和按时间排序的全部消息。
 *
 * @param conversation 会话元数据
 * @param messages 会话消息列表
 */
@Schema(description = "问答会话详情")
public record ConversationDetailResponse(
        @Schema(description = "会话元数据") ConversationResponse conversation,
        @Schema(description = "会话消息列表") List<ConversationMessageResponse> messages
) {

    public ConversationDetailResponse {
        // 消息列表统一不可变，保证响应对象不可被上层修改
        messages = List.copyOf(messages);
    }
}

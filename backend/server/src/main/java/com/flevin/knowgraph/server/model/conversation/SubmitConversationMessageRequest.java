package com.flevin.knowgraph.server.model.conversation;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 向问答会话提交问题请求。
 *
 * @param question 用户问题文本
 */
@Schema(description = "提交问答消息请求")
public record SubmitConversationMessageRequest(
        @Schema(description = "用户问题", example = "年会活动方案里场地定在哪里？")
        @NotBlank(message = "问题不能为空")
        @Size(max = 4000, message = "问题不能超过 4000 字符")
        String question
) {
}

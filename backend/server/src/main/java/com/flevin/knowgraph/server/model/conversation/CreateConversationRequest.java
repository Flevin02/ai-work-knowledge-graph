package com.flevin.knowgraph.server.model.conversation;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * 创建只读问答会话请求。
 *
 * @param title 可选会话标题；为空时由服务端生成默认标题
 * @param scopeDocumentId 可选当前文档范围标识；为空表示空间级问答
 */
@Schema(description = "创建问答会话请求")
public record CreateConversationRequest(
        @Schema(description = "可选会话标题", example = "年会方案答疑")
        @Size(max = 200, message = "会话标题不能超过 200 字符")
        String title,

        @Schema(description = "可选当前文档范围标识", example = "123456789012345")
        Long scopeDocumentId
) {
}

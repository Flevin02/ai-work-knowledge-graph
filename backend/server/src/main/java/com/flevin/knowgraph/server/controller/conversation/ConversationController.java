package com.flevin.knowgraph.server.controller.conversation;

import com.flevin.knowgraph.common.model.ApiResponse;
import com.flevin.knowgraph.server.model.conversation.ConversationDetailResponse;
import com.flevin.knowgraph.server.model.conversation.ConversationMessageResponse;
import com.flevin.knowgraph.server.model.conversation.ConversationResponse;
import com.flevin.knowgraph.server.model.conversation.CreateConversationRequest;
import com.flevin.knowgraph.server.model.conversation.SubmitConversationMessageRequest;
import com.flevin.knowgraph.server.service.conversation.ConversationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识空间内只读问答会话接口。
 */
@RestController
@RequestMapping("/v1/spaces/{spaceId}/conversations")
@Tag(name = "有据问答", description = "知识空间内只读问答会话、消息和引用定位")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;

    @PostMapping(value = "", name = "创建问答会话")
    @Operation(
            summary = "创建问答会话",
            description = "在当前知识空间创建只读问答会话，可圈定一份来源资料作为文档范围。"
    )
    public ApiResponse<ConversationResponse> createConversation(
            @Parameter(description = "知识空间标识") @PathVariable Long spaceId,
            @Valid @RequestBody CreateConversationRequest request
    ) {
        // 创建空间内只读问答会话并返回会话元数据
        return ApiResponse.success(conversationService.createConversation(spaceId, request));
    }

    @GetMapping(value = "/{conversationId}", name = "恢复问答会话")
    @Operation(
            summary = "恢复问答会话",
            description = "按空间隔离恢复会话元数据和全部消息，回答消息包含通过逐字反查的引用。"
    )
    public ApiResponse<ConversationDetailResponse> getConversation(
            @Parameter(description = "知识空间标识") @PathVariable Long spaceId,
            @Parameter(description = "会话标识") @PathVariable Long conversationId
    ) {
        // 恢复会话完整历史，用于刷新后恢复现场
        return ApiResponse.success(conversationService.getConversation(spaceId, conversationId));
    }

    @PostMapping(value = "/{conversationId}/messages", name = "提交问答消息")
    @Operation(
            summary = "提交问答消息",
            description = "提交问题并同步生成带引用的回答；引用必须通过服务端逐字反查，"
                    + "证据不足时回答标记 insufficient_evidence。"
    )
    public ApiResponse<ConversationMessageResponse> submitMessage(
            @Parameter(description = "知识空间标识") @PathVariable Long spaceId,
            @Parameter(description = "会话标识") @PathVariable Long conversationId,
            @Valid @RequestBody SubmitConversationMessageRequest request
    ) {
        // 提交问题并返回本轮回答消息及引用
        return ApiResponse.success(conversationService.submitMessage(spaceId, conversationId, request));
    }

    @GetMapping(value = "/{conversationId}/messages/{messageId}", name = "查询问答消息")
    @Operation(
            summary = "查询问答消息",
            description = "查询会话内一条消息及其引用，用于刷新后恢复单条回答。"
    )
    public ApiResponse<ConversationMessageResponse> getMessage(
            @Parameter(description = "知识空间标识") @PathVariable Long spaceId,
            @Parameter(description = "会话标识") @PathVariable Long conversationId,
            @Parameter(description = "消息标识") @PathVariable Long messageId
    ) {
        // 恢复单条消息及其引用
        return ApiResponse.success(conversationService.getMessage(spaceId, conversationId, messageId));
    }
}

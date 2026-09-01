package com.flevin.knowgraph.server.service.conversation;

import com.flevin.knowgraph.server.model.conversation.ConversationDetailResponse;
import com.flevin.knowgraph.server.model.conversation.ConversationMessageResponse;
import com.flevin.knowgraph.server.model.conversation.ConversationResponse;
import com.flevin.knowgraph.server.model.conversation.CreateConversationRequest;
import com.flevin.knowgraph.server.model.conversation.SubmitConversationMessageRequest;

/**
 * 知识空间内只读问答固定 Pipeline 服务。
 */
public interface ConversationService {

    /**
     * 在当前知识空间创建只读问答会话。
     *
     * @param spaceId 知识空间标识
     * @param request 会话标题和可选文档范围
     * @return 新建会话元数据
     */
    ConversationResponse createConversation(
            Long spaceId,
            CreateConversationRequest request
    );

    /**
     * 恢复会话元数据和全部消息（含回答引用）。
     *
     * @param spaceId 知识空间标识
     * @param conversationId 会话标识
     * @return 会话详情
     */
    ConversationDetailResponse getConversation(
            Long spaceId,
            Long conversationId
    );

    /**
     * 提交问题并同步生成带引用的回答；切片一通过供应商无关客户端完成，
     * 未接入生产客户端时回答记录为失败状态，用户消息始终保留。
     *
     * @param spaceId 知识空间标识
     * @param conversationId 会话标识
     * @param request 用户问题
     * @return 本轮助手回答消息及引用
     */
    ConversationMessageResponse submitMessage(
            Long spaceId,
            Long conversationId,
            SubmitConversationMessageRequest request
    );

    /**
     * 查询会话内一条消息及其引用，用于刷新后恢复单条回答。
     *
     * @param spaceId 知识空间标识
     * @param conversationId 会话标识
     * @param messageId 消息标识
     * @return 消息详情
     */
    ConversationMessageResponse getMessage(
            Long spaceId,
            Long conversationId,
            Long messageId
    );
}

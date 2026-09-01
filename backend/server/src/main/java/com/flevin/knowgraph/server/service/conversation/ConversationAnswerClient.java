package com.flevin.knowgraph.server.service.conversation;

import com.flevin.knowgraph.server.model.conversation.ConversationAnswerRequest;
import com.flevin.knowgraph.server.model.conversation.ConversationAnswerResult;

/**
 * 有据问答客户端领域抽象。
 *
 * <p>与 {@code DocumentAssociationClient}、{@code DocumentTaggingClient} 同位，
 * 隔离具体模型协议和供应商：实现类不得向业务层暴露 ChatModel、Prompt 模板
 * 或 OpenAI-compatible 客户端类型。客户端只能引用请求分片集合内的局部
 * chunkId，数据库标识、引用逐字反查和证据状态判定始终由服务端完成。</p>
 */
public interface ConversationAnswerClient {

    /**
     * 客户端实现标识，用于运行追溯；例如 fake 或 openai-compatible。
     *
     * @return 稳定的客户端实现标识
     */
    String clientId();

    /**
     * 根据用户问题和服务端召回的上下文分片生成带引用的回答候选。
     *
     * @param request 包含用户问题和上下文分片的请求
     * @return 回答文本和候选引用；证据不足时应返回空引用列表
     */
    ConversationAnswerResult answer(ConversationAnswerRequest request);
}

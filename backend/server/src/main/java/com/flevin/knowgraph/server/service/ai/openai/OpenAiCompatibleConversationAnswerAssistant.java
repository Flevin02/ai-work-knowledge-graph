package com.flevin.knowgraph.server.service.ai.openai;

import com.flevin.knowgraph.server.model.conversation.ConversationAnswerResult;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * OpenAI-compatible LangChain4j AI Service，通过返回类型约束有据问答结构。
 */
interface OpenAiCompatibleConversationAnswerAssistant {

    /**
     * 根据用户问题和服务端限定的分片生成回答与引用候选。
     *
     * @param conversationContext 用户问题与可引用分片上下文
     * @return 模型结构化回答结果
     */
    @SystemMessage(fromResource = "prompts/conversation-answer-system.md")
    ConversationAnswerResult answer(@UserMessage String conversationContext);
}

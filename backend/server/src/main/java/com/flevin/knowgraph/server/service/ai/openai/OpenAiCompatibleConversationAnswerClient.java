package com.flevin.knowgraph.server.service.ai.openai;

import com.flevin.knowgraph.server.model.ai.rag.DocumentChunk;
import com.flevin.knowgraph.server.model.conversation.ConversationAnswerRequest;
import com.flevin.knowgraph.server.model.conversation.ConversationAnswerResult;
import com.flevin.knowgraph.server.service.conversation.ConversationAnswerClient;
import com.flevin.knowgraph.server.service.conversation.ConversationAnswerInvalidOutputException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.output.OutputParsingException;

import java.util.List;
import java.util.Objects;

/**
 * OpenAI-compatible 有据问答适配器，把 LangChain4j AI Service 隔离在领域接口之外。
 *
 * <p>本类只负责渲染服务端已经限定的问题与分片上下文，并把模型结构化输出映射为回答
 * 候选。分片归属、引用逐字反查、证据状态和持久化继续由 {@code ConversationService}
 * 负责，模型输出本身不构成可信事实。</p>
 */
public class OpenAiCompatibleConversationAnswerClient implements ConversationAnswerClient {

    private final OpenAiCompatibleConversationAnswerAssistant assistant;

    /**
     * 创建无聊天记忆的生产问答客户端。
     *
     * @param chatModel OpenAI-compatible 聊天模型
     */
    public OpenAiCompatibleConversationAnswerClient(ChatModel chatModel) {
        // 在创建 AI Service 前拒绝空聊天模型，避免延迟到首次请求才失败
        Objects.requireNonNull(chatModel, "有据问答聊天模型不能为空");

        // 每次问题独立调用模型，结构化输出由 Assistant 返回类型约束
        this.assistant = AiServices.builder(OpenAiCompatibleConversationAnswerAssistant.class)
                .chatModel(chatModel)
                .build();
    }

    /**
     * 返回可持久化追溯的生产客户端标识。
     *
     * @return 固定的 OpenAI-compatible 客户端标识
     */
    @Override
    public String clientId() {
        return "openai-compatible";
    }

    /**
     * 根据用户问题和服务端限定分片生成回答与引用候选。
     *
     * @param request 包含用户问题和可引用分片的供应商无关请求
     * @return 结构化回答与引用候选；引用尚未经过服务端逐字反查
     */
    @Override
    public ConversationAnswerResult answer(ConversationAnswerRequest request) {
        // 在产生模型调用前拒绝空领域请求，避免发送无意义的外部请求
        Objects.requireNonNull(request, "有据问答请求不能为空");

        ConversationAnswerResult result;
        try {
            // 把安全上下文交给无聊天记忆的结构化 Assistant，禁止客户端自行检索事实库
            result = assistant.answer(buildConversationContext(request));
        } catch (OutputParsingException exception) {
            // 框架解析失败转换为领域异常，业务层无需依赖 LangChain4j 类型或模型原始响应
            throw new ConversationAnswerInvalidOutputException(
                    "有据问答模型返回结构无法解析",
                    exception
            );
        }
        if (result == null) {
            throw new ConversationAnswerInvalidOutputException("有据问答模型返回了空结果");
        }

        // 回答正文是消息事实的必填内容，空正文不能伪装为成功回答
        if (result.answer() == null || result.answer().isBlank()) {
            throw new ConversationAnswerInvalidOutputException("有据问答模型回答正文不能为空");
        }
        return result;
    }

    /**
     * 把用户问题和可引用分片渲染为确定性的模型输入。
     *
     * @param request 服务端限定的问答请求
     * @return 不包含数据库标识和存储路径的用户消息
     */
    private String buildConversationContext(ConversationAnswerRequest request) {
        StringBuilder context = new StringBuilder();

        // 明确当前问题，要求模型只回答该问题而不是续写上下文
        context.append("## 用户问题\n")
                .append(request.question())
                .append("\n\n## 可引用分片\n");

        List<DocumentChunk> chunks = request.contextChunks();
        if (chunks.isEmpty()) {
            // 空上下文必须显式呈现，配合系统 Prompt 返回资料不足而不是猜测
            context.append("（无可引用分片）\n");
            return context.toString();
        }

        // 保留服务端分片顺序，模型引用时只能复制给定的局部 chunkId 和逐字原文
        for (DocumentChunk chunk : chunks) {
            context.append("- 分片标识：").append(chunk.chunkId())
                    .append("；章节路径：").append(chunk.sectionPath()).append('\n');
            context.append("  原文：").append(chunk.contentText()).append('\n');
        }
        return context.toString();
    }
}

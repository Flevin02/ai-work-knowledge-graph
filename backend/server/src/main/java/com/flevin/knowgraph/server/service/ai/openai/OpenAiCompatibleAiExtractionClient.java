package com.flevin.knowgraph.server.service.ai.openai;

import com.flevin.knowgraph.server.model.ai.AiExtractionRequest;
import com.flevin.knowgraph.server.model.ai.AiExtractionResult;
import com.flevin.knowgraph.server.service.ai.AiExtractionClient;
import com.flevin.knowgraph.server.service.ai.AiExtractionResultValidator;
import com.flevin.knowgraph.server.service.ai.AiExtractionValidationException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.output.JsonSchemas;
import dev.langchain4j.service.output.ServiceOutputParser;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/**
 * OpenAI-compatible 结构化抽取适配器，把 LangChain4j 实现隔离在领域接口之外。
 */
public class OpenAiCompatibleAiExtractionClient implements AiExtractionClient {

    private final OpenAiCompatibleExtractionAssistant assistant;
    private final AiExtractionResultValidator validator;
    private final String promptVersion;
    private final String schemaVersion;
    private final boolean jsonSchemaEnabled;
    private final ServiceOutputParser outputParser;

    public OpenAiCompatibleAiExtractionClient(
            ChatModel chatModel,
            StreamingChatModel streamingChatModel,
            AiExtractionResultValidator validator,
            String promptVersion,
            String schemaVersion,
            boolean jsonSchemaEnabled
    ) {
        this.outputParser = new ServiceOutputParser();
        AiServices<OpenAiCompatibleExtractionAssistant> assistantBuilder = AiServices.builder(
                        OpenAiCompatibleExtractionAssistant.class
                )
                .chatModel(chatModel)
                .streamingChatModel(streamingChatModel);

        if (jsonSchemaEnabled) {
            // 为 TokenStream 请求显式补入结构化响应格式，避免流式返回类型丢失目标 DTO Schema
            ResponseFormat responseFormat = ResponseFormat.builder()
                    .type(ResponseFormatType.JSON)
                    .jsonSchema(JsonSchemas.jsonSchemaFrom(AiExtractionResult.class)
                            .orElseThrow(() -> new IllegalStateException("无法生成 AI 抽取 JSON Schema")))
                    .build();

            // 只在流式请求尚未携带响应格式时补充 Schema，保留同步 AI Service 的既有行为
            assistantBuilder.chatRequestTransformer(request -> request.responseFormat() == null
                    ? request.toBuilder().responseFormat(responseFormat).build()
                    : request);
        }

        // 同一个无聊天记忆的 AI Service 分别承载同步和流式模型调用
        this.assistant = assistantBuilder.build();
        this.validator = validator;
        this.promptVersion = promptVersion;
        this.schemaVersion = schemaVersion;
        this.jsonSchemaEnabled = jsonSchemaEnabled;
    }

    /**
     * 调用自定义 OpenAI-compatible 模型提取来源分片并验证结果。
     *
     * @param request 包含来源定位和原文的抽取请求
     * @return 经过结构和证据校验的候选结果
     */
    @Override
    public AiExtractionResult extract(AiExtractionRequest request) {
        // 在产生远程调用和费用前校验来源定位和原文输入
        validator.validateRequest(request);

        // 构建包含版本和来源定位的稳定用户上下文
        String sourceContext = buildSourceContext(request);

        // 调用 LangChain4j AI Service 获取结构化模型输出
        AiExtractionResult result = assistant.extract(sourceContext);

        // 校验 DTO 结构、引用完整性和逐字原文证据
        return validator.validate(request, result);
    }

    /**
     * 调用 OpenAI-compatible 流式接口，转发真实文本增量，并在完整响应后统一校验结果。
     *
     * @param request 包含来源定位和原文的抽取请求
     * @param deltaConsumer 模型原始文本增量消费者
     * @return 完整响应通过结构和证据校验后的候选结果
     */
    @Override
    public AiExtractionResult extract(
            AiExtractionRequest request,
            Consumer<String> deltaConsumer
    ) {
        Objects.requireNonNull(deltaConsumer, "模型增量消费者不能为空");

        // 在产生远程调用和费用前校验来源定位和原文输入
        validator.validateRequest(request);

        // 构建包含版本和来源定位的稳定用户上下文
        String sourceContext = buildSourceContext(request);
        if (!jsonSchemaEnabled) {
            // 非原生 JSON Schema 模式显式附加 DTO 输出格式，保持流式与同步结构约束一致
            sourceContext = sourceContext + System.lineSeparator()
                    + outputParser.outputFormatInstructions(AiExtractionResult.class);
        }

        CompletableFuture<AiExtractionResult> resultFuture = new CompletableFuture<>();

        // 创建当前分片的真实模型流，并注册增量、完成和失败回调
        TokenStream tokenStream = assistant.extractStreaming(sourceContext)
                .onPartialResponse(delta -> publishDelta(delta, deltaConsumer))
                .onCompleteResponse(response -> completeValidatedResult(
                        request,
                        response.aiMessage().text(),
                        resultFuture
                ))
                .onError(resultFuture::completeExceptionally);

        // 启动供应商流式请求，完整结果仍由当前调用串行等待后返回
        tokenStream.start();

        try {
            // 等待完整响应完成，供应商超时仍由模型客户端配置控制
            return resultFuture.join();
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("AI 流式调用异常结束", cause);
        }
    }

    /**
     * 转发供应商实际返回的非空文本增量，不合成服务端占位内容。
     *
     * @param delta 供应商文本增量
     * @param deltaConsumer 调用方增量消费者
     */
    private void publishDelta(
            String delta,
            Consumer<String> deltaConsumer
    ) {
        if (delta == null || delta.isEmpty()) {
            return;
        }

        // 把真实模型文本交给上层事件协议，当前适配器不持久化部分输出
        deltaConsumer.accept(delta);
    }

    /**
     * 将完整流式文本解析为 DTO，并执行与同步链路相同的证据校验。
     *
     * @param request 当前来源分片请求
     * @param responseText 完整模型文本
     * @param resultFuture 等待完整候选结果的 Future
     */
    private void completeValidatedResult(
            AiExtractionRequest request,
            String responseText,
            CompletableFuture<AiExtractionResult> resultFuture
    ) {
        try {
            // 使用 LangChain4j 的 DTO 输出解析器还原完整结构化结果
            AiExtractionResult result = (AiExtractionResult) outputParser.parseText(
                    AiExtractionResult.class,
                    responseText
            );

            // 完整结果通过结构、引用和逐字证据校验后才交给业务层
            resultFuture.complete(validator.validate(request, result));
        } catch (AiExtractionValidationException exception) {
            resultFuture.completeExceptionally(exception);
        } catch (RuntimeException exception) {
            resultFuture.completeExceptionally(new AiExtractionValidationException(
                    "AI 流式输出不是有效的结构化结果",
                    exception
            ));
        }
    }

    /**
     * 构建可追溯且不混入数据库职责的模型输入。
     *
     * @param request 当前来源分片请求
     * @return 带来源定位和版本信息的用户消息
     */
    private String buildSourceContext(AiExtractionRequest request) {
        return """
                promptVersion: %s
                schemaVersion: %s
                sourceDocumentId: %s
                documentName: %s
                documentType: %s
                chunkId: %s
                sectionPath: %s

                原文开始：
                %s
                原文结束。
                """.formatted(
                promptVersion,
                schemaVersion,
                request.sourceDocumentId(),
                request.documentName(),
                request.documentType(),
                request.chunkId(),
                request.sectionPath(),
                request.content()
        );
    }
}

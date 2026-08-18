package com.flevin.knowgraph.server.service.ai.openai;

import com.flevin.knowgraph.server.model.ai.AiExtractionRequest;
import com.flevin.knowgraph.server.model.ai.AiExtractionResult;
import com.flevin.knowgraph.server.service.ai.AiExtractionClient;
import com.flevin.knowgraph.server.service.ai.AiExtractionResultValidator;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;

/**
 * OpenAI-compatible 结构化抽取适配器，把 LangChain4j 实现隔离在领域接口之外。
 */
public class OpenAiCompatibleAiExtractionClient implements AiExtractionClient {

    private final OpenAiCompatibleExtractionAssistant assistant;
    private final AiExtractionResultValidator validator;
    private final String promptVersion;
    private final String schemaVersion;

    public OpenAiCompatibleAiExtractionClient(
            ChatModel chatModel,
            AiExtractionResultValidator validator,
            String promptVersion,
            String schemaVersion
    ) {
        // 使用返回类型 Schema 创建无聊天记忆的结构化抽取服务
        this.assistant = AiServices.builder(OpenAiCompatibleExtractionAssistant.class)
                .chatModel(chatModel)
                .build();
        this.validator = validator;
        this.promptVersion = promptVersion;
        this.schemaVersion = schemaVersion;
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

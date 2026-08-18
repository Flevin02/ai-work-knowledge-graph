package com.flevin.knowgraph.server.config;

import com.flevin.knowgraph.server.config.properties.AiProperties;
import com.flevin.knowgraph.server.service.ai.AiExtractionClient;
import com.flevin.knowgraph.server.service.ai.AiExtractionResultValidator;
import com.flevin.knowgraph.server.service.ai.openai.OpenAiCompatibleAiExtractionClient;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static dev.langchain4j.model.chat.Capability.RESPONSE_FORMAT_JSON_SCHEMA;

/**
 * LangChain4j OpenAI-compatible 模型配置，仅在显式启用 AI 时创建真实客户端。
 */
@Configuration
@ConditionalOnProperty(prefix = "ai", name = "enabled", havingValue = "true")
@ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${ai.api-key:}')")
public class AiConfiguration {

    /**
     * 创建使用自定义 Base URL 和模型名的 OpenAI-compatible 聊天模型。
     *
     * @param properties AI 模型配置
     * @return LangChain4j 聊天模型
     */
    @Bean
    public ChatModel openAiCompatibleChatModel(AiProperties properties) {
        // 校验真实模型调用需要的协议、地址和密钥配置
        validateOpenAiCompatibleProperties(properties);

        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .apiKey(properties.getApiKey())
                .baseUrl(properties.getBaseUrl())
                .modelName(properties.getModel())
                .temperature(properties.getTemperature())
                .maxCompletionTokens(properties.getMaxOutputTokens())
                .maxRetries(properties.getMaxRetries())
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .logRequests(false)
                .logResponses(false);

        if (properties.isJsonSchemaEnabled()) {
            // 仅在兼容端点确认支持时启用原生 JSON Schema，避免协议实现不完整导致请求失败
            builder.supportedCapabilities(RESPONSE_FORMAT_JSON_SCHEMA)
                    .strictJsonSchema(true);
        }

        // 构建不记录敏感请求正文的 OpenAI-compatible 聊天模型
        return builder.build();
    }

    /**
     * 创建可选的 OpenAI-compatible Embedding 模型。
     *
     * @param properties AI 模型配置
     * @return 文档和查询共用的向量化模型
     */
    @Bean
    @ConditionalOnProperty(prefix = "ai", name = "embedding-enabled", havingValue = "true")
    public EmbeddingModel openAiCompatibleEmbeddingModel(AiProperties properties) {
        // 校验真实 Embedding 调用需要的协议、地址、模型和密钥配置
        validateEmbeddingProperties(properties);

        // 创建与聊天端点使用相同认证和 Base URL 的 Embedding 模型
        return OpenAiEmbeddingModel.builder()
                .apiKey(properties.getApiKey())
                .baseUrl(properties.getBaseUrl())
                .modelName(properties.getEmbeddingModel())
                .maxRetries(properties.getMaxRetries())
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .logRequests(false)
                .logResponses(false)
                .build();
    }

    /**
     * 创建通过领域接口隔离的结构化抽取客户端。
     *
     * @param chatModel OpenAI-compatible 聊天模型
     * @param validator 结构化抽取结果校验器
     * @param properties AI 模型配置
     * @return 领域层 AI 抽取客户端
     */
    @Bean
    public AiExtractionClient aiExtractionClient(
            @Qualifier("openAiCompatibleChatModel") ChatModel chatModel,
            AiExtractionResultValidator validator,
            AiProperties properties
    ) {
        return new OpenAiCompatibleAiExtractionClient(
                chatModel,
                validator,
                properties.getPromptVersion(),
                properties.getSchemaVersion()
        );
    }

    /**
     * 校验当前启用的自定义模型使用受支持的 OpenAI-compatible 协议。
     *
     * @param properties AI 模型配置
     */
    private void validateOpenAiCompatibleProperties(AiProperties properties) {
        if (!"openai-compatible".equalsIgnoreCase(properties.getProvider())) {
            throw new IllegalStateException("当前真实模型适配仅支持 openai-compatible 协议");
        }
        if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()) {
            throw new IllegalStateException("启用 AI 时必须配置 AI_BASE_URL");
        }
        if (properties.getModel() == null || properties.getModel().isBlank()) {
            throw new IllegalStateException("启用 AI 时必须配置 AI_MODEL");
        }
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new IllegalStateException("启用 AI 时必须通过 AI_API_KEY 提供模型密钥");
        }
    }

    /**
     * 校验可选 Embedding 模型配置。
     *
     * @param properties AI 模型配置
     */
    private void validateEmbeddingProperties(AiProperties properties) {
        // 复用聊天端点的协议、地址和认证校验
        validateOpenAiCompatibleProperties(properties);

        if (properties.getEmbeddingModel() == null || properties.getEmbeddingModel().isBlank()) {
            throw new IllegalStateException("启用 Embedding 时必须配置 AI_EMBEDDING_MODEL");
        }
    }
}

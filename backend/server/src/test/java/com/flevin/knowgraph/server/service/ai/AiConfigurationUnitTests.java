package com.flevin.knowgraph.server.service.ai;

import com.flevin.knowgraph.server.config.AiConfiguration;
import com.flevin.knowgraph.server.config.properties.AiProperties;
import com.flevin.knowgraph.server.model.ai.embedding.EmbeddingModelDescriptor;
import com.flevin.knowgraph.server.service.ai.embedding.DocumentEmbeddingClient;
import com.flevin.knowgraph.server.service.ai.embedding.OpenAiCompatibleDocumentEmbeddingClient;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AI 配置的轻量单元验证，避免 Embedding-only 实验依赖 Spring 上下文或真实外部调用。
 */
class AiConfigurationUnitTests {

    /**
     * 验证独立 Embedding 配置可以在聊天模型关闭时创建真实向量客户端。
     */
    @Test
    void createsEmbeddingClientFromIndependentEmbeddingPropertiesWithoutChatKey() {
        // 构造只包含 Embedding 端点和密钥的配置，模拟本地 Ollama 评估环境
        AiProperties properties = embeddingOnlyProperties();
        AiConfiguration configuration = new AiConfiguration();

        // 使用独立 Embedding 配置创建 LangChain4j 向量模型，不要求聊天模型密钥
        EmbeddingModel embeddingModel = configuration.openAiCompatibleEmbeddingModel(properties);

        // 将基础设施模型包装为领域抽象，供索引和召回服务按描述隔离版本
        DocumentEmbeddingClient client = configuration.realDocumentEmbeddingClient(
                embeddingModel,
                properties
        );

        // 校验真实客户端和版本描述，防止真实评估误落到 Fake Embedding
        assertThat(client).isInstanceOf(OpenAiCompatibleDocumentEmbeddingClient.class);
        EmbeddingModelDescriptor descriptor = client.descriptor();
        assertThat(descriptor.provider()).isEqualTo("openai-compatible");
        assertThat(descriptor.model()).isEqualTo("qwen3-embedding:latest");
        assertThat(descriptor.version()).isEqualTo("ollama-qwen3-embedding-latest-20260902");
        assertThat(descriptor.dimension()).isEqualTo(4096);
    }

    /**
     * 验证启用真实 Embedding 时必须显式提供可追溯的认证配置。
     */
    @Test
    void rejectsEmbeddingClientWhenEffectiveEmbeddingKeyIsMissing() {
        // 构造缺少 Embedding 和聊天密钥的配置，覆盖误用 Fake 的失败边界
        AiProperties properties = embeddingOnlyProperties();
        properties.setEmbeddingApiKey(null);
        properties.setApiKey(null);
        AiConfiguration configuration = new AiConfiguration();

        // 真实 Embedding 配置缺失时应在客户端创建阶段失败，而不是静默退回 Fake
        assertThatThrownBy(() -> configuration.openAiCompatibleEmbeddingModel(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AI_EMBEDDING_API_KEY");
    }

    /**
     * 构造本地 Ollama Embedding-only 评估使用的最小配置。
     *
     * @return 不包含聊天模型密钥的 AI 配置
     */
    private AiProperties embeddingOnlyProperties() {
        AiProperties properties = new AiProperties();
        properties.setEnabled(false);
        properties.setEmbeddingEnabled(true);
        properties.setProvider("openai-compatible");
        properties.setApiKey(null);
        properties.setBaseUrl(null);
        properties.setEmbeddingBaseUrl("http://127.0.0.1:11434/v1");
        properties.setEmbeddingApiKey("test-only-embedding-key");
        properties.setEmbeddingModel("qwen3-embedding:latest");
        properties.setEmbeddingDimension(4096);
        properties.setEmbeddingVersion("ollama-qwen3-embedding-latest-20260902");
        return properties;
    }
}

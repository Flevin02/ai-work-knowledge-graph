package com.flevin.knowgraph.server.service.ai;

import com.flevin.knowgraph.server.config.properties.AiProperties;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "app.database-path=target/test-data/ai-configuration.sqlite",
        "app.upload-dir=target/test-data/ai-configuration-uploads",
        "ai.enabled=true",
        "ai.provider=openai-compatible",
        "ai.api-key=test-only-key",
        "ai.base-url=https://api.psydo.top/v1",
        "ai.model=gpt-5.4-mini",
        "ai.embedding-enabled=false"
})
class AiConfigurationIntegrationTests {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private AiProperties aiProperties;

    @Autowired
    @Qualifier("openAiCompatibleChatModel")
    private ChatModel chatModel;

    @Autowired
    private AiExtractionClient aiExtractionClient;

    @Test
    void createsCustomOpenAiCompatibleChatClientWithoutCallingRemoteModel() {
        assertThat(aiProperties.getProvider()).isEqualTo("openai-compatible");
        assertThat(aiProperties.getBaseUrl()).isEqualTo("https://api.psydo.top/v1");
        assertThat(aiProperties.getModel()).isEqualTo("gpt-5.4-mini");
        assertThat(chatModel).isInstanceOf(OpenAiChatModel.class);
        assertThat(aiExtractionClient).isInstanceOf(
                com.flevin.knowgraph.server.service.ai.openai.OpenAiCompatibleAiExtractionClient.class
        );

        // Embedding 需要单独确认模型权限，默认关闭时不能创建真实向量化客户端
        assertThat(applicationContext.getBeansOfType(EmbeddingModel.class)).isEmpty();
    }
}

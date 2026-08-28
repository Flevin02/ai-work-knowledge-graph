package com.flevin.knowgraph.server.service.ai;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 AI 连通性烟测，仅在 Maven {@code real-ai} Profile 中执行。
 *
 * <p>该测试发送一条虚构聊天提示和两条虚构 Embedding 文本，用于确认当前环境的模型端点、认证、维度
 * 与有限向量值均可用。它不记录 API Key、完整向量或真实资料，也不替代 Fake 自动回归、召回质量评估或生产验证。</p>
 */
@Tag("real-ai")
@SpringBootTest(properties = {
        "app.upload-dir=target/test-data/real-ai-smoke-uploads",
        "ai.enabled=true",
        "ai.embedding-enabled=true",
        "ai.json-schema-enabled=false",
        "ai.max-retries=0",
        "ai.timeout-seconds=30"
})
class RealAiSmokeIntegrationTests {

    @Autowired
    private ObjectProvider<ChatModel> chatModelProvider;

    @Autowired
    private ObjectProvider<EmbeddingModel> embeddingModelProvider;

    /**
     * 使用真实聊天和 Embedding 客户端验证端点连通、非空输出和向量基础约束。
     *
     * @throws Exception 真实服务调用失败时抛出，保留 Maven 失败证据
     */
    @Test
    void callsRealChatAndEmbeddingModelsWithFictionalSmokeInputs() throws Exception {
        // 在真实调用前显式校验环境变量，避免无密钥时把条件装配失败误判为模型故障
        assertThat(System.getenv("AI_API_KEY"))
                .as("真实 AI 烟测需要通过环境变量提供 AI_API_KEY")
                .isNotBlank();

        // 获取真实聊天模型，确认当前环境已同时启用 AI 和有效认证
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        assertThat(chatModel)
                .as("真实 AI 烟测需要创建 ChatModel，请检查 AI_ENABLED、AI_API_KEY、AI_BASE_URL 和 AI_MODEL")
                .isNotNull();

        // 使用固定虚构提示验证最小聊天请求可返回非空文本，不记录响应正文
        String chatResponse = chatModel.chat("这是自动化连通性烟测，请只回复“已连接”。");
        assertThat(chatResponse).isNotBlank();

        // 获取真实 Embedding 模型，确认聊天与向量化模型可被独立配置和启用
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        assertThat(embeddingModel)
                .as("真实 AI 烟测需要创建 EmbeddingModel，请检查 AI_EMBEDDING_ENABLED 和 AI_EMBEDDING_MODEL")
                .isNotNull();

        // 对两条语义相近的虚构文本执行独立向量化，避免把向量内容写入日志或断言输出
        Embedding firstEmbedding = embeddingModel.embed("虚构项目的资料导入流程需要保留原文证据。").content();
        Embedding secondEmbedding = embeddingModel.embed("虚构项目要求每份导入资料都可以回溯到原始证据。").content();

        // 校验同一模型返回的向量维度一致且元素均为有限值，作为写入可重建向量事实前的最低协议门槛
        assertThat(firstEmbedding.dimension()).isPositive();
        assertThat(secondEmbedding.dimension()).isEqualTo(firstEmbedding.dimension());
        // 逐元素验证首条向量不存在 NaN 或无穷值，避免在测试输出中暴露完整向量
        assertVectorContainsOnlyFiniteValues(firstEmbedding.vector());
        // 逐元素验证第二条向量不存在 NaN 或无穷值，确认同一端点的基础返回稳定
        assertVectorContainsOnlyFiniteValues(secondEmbedding.vector());
    }

    /**
     * 校验 Embedding 向量的每个分量都可被下游向量数据库安全使用。
     *
     * @param vector 待检查的 Embedding 向量，不会写入日志或断言错误消息
     */
    private void assertVectorContainsOnlyFiniteValues(float[] vector) {
        // 逐个检查分量，避免断言框架在失败时格式化输出完整向量
        for (float vectorValue : vector) {
            // 断言当前向量分量不是 NaN、正无穷或负无穷
            assertThat(Float.isFinite(vectorValue)).isTrue();
        }
    }
}

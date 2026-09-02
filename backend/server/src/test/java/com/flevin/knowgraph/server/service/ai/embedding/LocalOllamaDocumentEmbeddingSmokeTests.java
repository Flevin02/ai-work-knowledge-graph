package com.flevin.knowgraph.server.service.ai.embedding;

import com.flevin.knowgraph.server.model.ai.embedding.EmbeddingModelDescriptor;
import com.flevin.knowgraph.server.model.ai.embedding.EmbeddingVector;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本地 Ollama Embedding 真实端点烟测，只验证向量模型，不调用聊天模型或 MySQL。
 *
 * <p>该测试通过 OpenAI-compatible {@code /v1/embeddings} 协议访问本机 Ollama，
 * 使用项目 {@link DocumentEmbeddingClient} 抽象校验批量返回数量、模型描述、维度和有限值。
 * 它只允许在显式 {@code real-ai} Profile 下运行，避免默认回归依赖本机模型服务。</p>
 */
@Tag("real-ai")
class LocalOllamaDocumentEmbeddingSmokeTests {

    /**
     * 使用本地 Ollama qwen3-embedding 执行最小批量向量化验证。
     */
    @Test
    void embedsFictionalTextsThroughLocalOllamaOpenAiCompatibleEndpoint() {
        // 读取本地 Embedding Base URL，确保测试不会误连外部供应商端点
        String baseUrl = requiredEnv("AI_EMBEDDING_BASE_URL");
        // 读取本地 Embedding API Key；Ollama OpenAI-compatible 接口接受本地占位值
        String apiKey = requiredEnv("AI_EMBEDDING_API_KEY");
        // 读取本地 Embedding 模型名，和向量事实版本共同组成可追溯模型描述
        String model = requiredEnv("AI_EMBEDDING_MODEL");
        // 读取本次向量事实版本，避免不同模型或维度的向量被视为同一批事实
        String version = requiredEnv("AI_EMBEDDING_VERSION");
        // 读取显式维度配置，测试会用真实返回值反向校验它
        int dimension = positiveIntEnv("AI_EMBEDDING_DIMENSION");

        // 构建 LangChain4j OpenAI-compatible Embedding 模型，只启用向量端点，不创建聊天客户端
        EmbeddingModel embeddingModel = OpenAiEmbeddingModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(model)
                .maxRetries(0)
                .timeout(Duration.ofSeconds(60))
                .logRequests(false)
                .logResponses(false)
                .build();

        // 用项目领域客户端包装真实模型，复用生产链路的数量和维度校验逻辑
        DocumentEmbeddingClient client = new OpenAiCompatibleDocumentEmbeddingClient(
                embeddingModel,
                new EmbeddingModelDescriptor(
                        "openai-compatible",
                        model,
                        version,
                        dimension
                )
        );

        // 对两条虚构文本执行批量向量化，验证本地端点的 OpenAI-compatible 批量协议
        List<EmbeddingVector> vectors = client.embed(List.of(
                "虚构年会项目需要根据来源资料建立可追溯知识图谱。",
                "虚构办公资料的问答引用必须能够回到原文证据。"
        ));

        // 校验客户端描述与本次环境变量完全一致，后续写入向量事实时可按版本隔离
        assertThat(client.descriptor()).isEqualTo(new EmbeddingModelDescriptor(
                "openai-compatible",
                model,
                version,
                dimension
        ));
        // 校验批量响应数量与输入文本一致，避免半批结果进入下游索引
        assertThat(vectors).hasSize(2);
        // 校验每条向量维度都等于显式配置值，防止模型切换后旧维度被误用
        assertThat(vectors).allSatisfy(vector -> assertThat(vector.dimension()).isEqualTo(dimension));
    }

    /**
     * 读取必填环境变量。
     *
     * @param name 环境变量名
     * @return 去除首尾空白后的环境变量值
     */
    private String requiredEnv(String name) {
        // 从当前测试进程读取环境变量，不使用源码或仓库配置中的默认供应商值
        String value = System.getenv(name);
        assertThat(value)
                .as("本地 Ollama Embedding smoke 需要设置环境变量 " + name)
                .isNotBlank();
        return value.strip();
    }

    /**
     * 读取正整数环境变量。
     *
     * @param name 环境变量名
     * @return 正整数值
     */
    private int positiveIntEnv(String name) {
        // 先按必填变量读取，再解析为整数维度配置
        String value = requiredEnv(name);
        // 将维度配置转换为整数，便于和真实向量长度逐条对比
        int parsed = Integer.parseInt(value);
        assertThat(parsed)
                .as("环境变量 " + name + " 必须是正整数")
                .isPositive();
        return parsed;
    }
}

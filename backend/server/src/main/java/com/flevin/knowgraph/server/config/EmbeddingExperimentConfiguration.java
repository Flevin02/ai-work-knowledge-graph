package com.flevin.knowgraph.server.config;

import com.flevin.knowgraph.server.service.ai.embedding.DeterministicFakeEmbeddingClient;
import com.flevin.knowgraph.server.service.ai.embedding.DocumentEmbeddingClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 阶段 3 Embedding 实验配置，仅提供不会产生外部调用的确定性 Fake 客户端。
 */
@Configuration
public class EmbeddingExperimentConfiguration {

    /**
     * 创建默认自动回归和本地数据流实验使用的 Fake Embedding 客户端。
     *
     * @return 固定 8 维且不访问外部服务的客户端
     */
    @Bean("fakeDocumentEmbeddingClient")
    public DocumentEmbeddingClient fakeDocumentEmbeddingClient() {
        // 使用固定算法和维度，确保相同输入在重复实验中得到同一向量
        return new DeterministicFakeEmbeddingClient();
    }
}

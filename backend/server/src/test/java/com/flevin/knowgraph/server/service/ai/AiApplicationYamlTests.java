package com.flevin.knowgraph.server.service.ai;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 主配置文件验证，确保本地 Embedding 默认值与真实 Ollama 模型保持一致。
 */
class AiApplicationYamlTests {

    /**
     * 验证主配置默认指向已部署的本地 Ollama 模型，同时保持真实 Embedding 默认关闭。
     *
     * @throws IOException 配置资源无法读取时抛出
     */
    @Test
    void configuresLocalOllamaEmbeddingWithoutEnablingExternalCallsByDefault() throws IOException {
        // 读取经过 Maven 资源处理的主配置，验证应用实际加载的 YAML 默认值
        List<PropertySource<?>> propertySources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        MockEnvironment environment = new MockEnvironment();

        // 按 Spring 配置优先级注册 YAML 属性源，使环境变量占位符使用声明的默认值
        propertySources.forEach(environment.getPropertySources()::addLast);

        assertThat(environment.getProperty("ai.embedding-enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("ai.embedding-base-url"))
                .isEqualTo("http://127.0.0.1:11434/v1");
        assertThat(environment.getProperty("ai.embedding-api-key")).isEqualTo("ollama");
        assertThat(environment.getProperty("ai.embedding-model")).isEqualTo("qwen3-embedding:latest");
        assertThat(environment.getProperty("ai.embedding-dimension", Integer.class)).isEqualTo(4096);
        assertThat(environment.getProperty("ai.embedding-version"))
                .isEqualTo("ollama-qwen3-embedding-latest-20260902");
    }
}

package com.flevin.knowgraph.server.service.ai;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI Prompt 类路径资源测试，防止源码存在但 Maven 构建未打包。
 */
class AiPromptResourceTests {

    @Test
    void packagesMarkdownSystemPromptOnRuntimeClasspath() throws IOException {
        ClassPathResource promptResource = new ClassPathResource(
                "prompts/prd-extraction-system.md"
        );

        // 验证 Maven 最终产物中存在 LangChain4j 所需系统提示词
        assertThat(promptResource.exists()).isTrue();

        // 读取 Markdown 提示词并验证关键结构，避免误打包空文件
        String promptContent = promptResource.getContentAsString(StandardCharsets.UTF_8);
        assertThat(promptContent)
                .contains("# 工作知识图谱结构化抽取器")
                .contains("## 分片摘要规则")
                .contains("## 证据规则")
                .contains("## 输出约束");
    }
}

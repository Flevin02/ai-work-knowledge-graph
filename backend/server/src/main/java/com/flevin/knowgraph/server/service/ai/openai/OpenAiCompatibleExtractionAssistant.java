package com.flevin.knowgraph.server.service.ai.openai;

import com.flevin.knowgraph.server.model.ai.AiExtractionResult;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

/**
 * OpenAI-compatible LangChain4j AI Service，通过返回类型约束抽取结构。
 */
interface OpenAiCompatibleExtractionAssistant {

    /**
     * 根据系统规则从单个来源分片提取结构化候选结果。
     *
     * @param sourceContext 带来源定位的用户上下文
     * @return 模型结构化结果
     */
    @SystemMessage(fromResource = "prompts/prd-extraction-system.md")
    AiExtractionResult extract(@UserMessage String sourceContext);

    /**
     * 根据系统规则流式返回单个来源分片的原始结构化文本。
     *
     * @param sourceContext 带来源定位和输出格式约束的用户上下文
     * @return 可监听模型文本增量和完整响应的流
     */
    @SystemMessage(fromResource = "prompts/prd-extraction-system.md")
    TokenStream extractStreaming(@UserMessage String sourceContext);
}

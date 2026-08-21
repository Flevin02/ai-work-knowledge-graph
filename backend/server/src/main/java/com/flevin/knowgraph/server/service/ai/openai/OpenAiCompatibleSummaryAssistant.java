package com.flevin.knowgraph.server.service.ai.openai;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * OpenAI-compatible LangChain4j 全文摘要服务，独立于结构化抽取 Schema。
 */
interface OpenAiCompatibleSummaryAssistant {

    /**
     * 根据按原文顺序排列的分片摘要生成文档级全文摘要。
     *
     * @param summaryContext 带来源定位和分片摘要素材的用户上下文
     * @return 单段自然中文全文摘要
     */
    @SystemMessage(fromResource = "prompts/document-summary-system.md")
    String summarizeDocument(@UserMessage String summaryContext);
}

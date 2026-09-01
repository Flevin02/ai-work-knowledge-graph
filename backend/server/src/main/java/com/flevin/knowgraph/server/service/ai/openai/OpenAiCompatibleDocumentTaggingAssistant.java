package com.flevin.knowgraph.server.service.ai.openai;

import com.flevin.knowgraph.server.model.tag.DocumentTaggingResult;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * OpenAI-compatible LangChain4j AI Service，通过返回类型约束文档标签结构。
 */
interface OpenAiCompatibleDocumentTaggingAssistant {

    /**
     * 根据系统规则从服务端限定的分片中生成摘要与标签候选。
     *
     * @param taggingContext 带当前文档与可引用分片的用户上下文
     * @return 模型结构化标签结果
     */
    @SystemMessage(fromResource = "prompts/document-tagging-system.md")
    DocumentTaggingResult tag(@UserMessage String taggingContext);
}

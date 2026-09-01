package com.flevin.knowgraph.server.service.ai.openai;

import com.flevin.knowgraph.server.model.association.DocumentAssociationResult;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * OpenAI-compatible LangChain4j AI Service，通过返回类型约束文档关联判断结构。
 */
interface OpenAiCompatibleDocumentAssociationAssistant {

    /**
     * 根据系统规则对服务端候选集合逐一判断文档关系。
     *
     * @param associationContext 带当前文档、候选集合和可引用分片的用户上下文
     * @return 模型结构化关联判断结果
     */
    @SystemMessage(fromResource = "prompts/document-association-system.md")
    DocumentAssociationResult associate(@UserMessage String associationContext);
}

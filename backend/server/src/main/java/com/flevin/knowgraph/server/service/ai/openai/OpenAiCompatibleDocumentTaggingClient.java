package com.flevin.knowgraph.server.service.ai.openai;

import com.flevin.knowgraph.server.model.ai.rag.DocumentChunk;
import com.flevin.knowgraph.server.model.tag.DocumentTaggingDocumentContext;
import com.flevin.knowgraph.server.model.tag.DocumentTaggingRequest;
import com.flevin.knowgraph.server.model.tag.DocumentTaggingResult;
import com.flevin.knowgraph.server.service.tag.DocumentTaggingClient;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;

import java.util.List;
import java.util.Objects;

/**
 * OpenAI-compatible 文档标签抽取适配器，把 LangChain4j AI Service 隔离在领域接口之外。
 *
 * <p>服务端 Pipeline 已完成分片组装、摘要与标签名规范化、证据反查和审核落库；本类只负责把安全
 * 上下文渲染为模型输入并调用结构化标签抽取。模型输出不构成标签事实，正式标签仍必须经过服务端
 * 校验和人工审核。</p>
 */
public class OpenAiCompatibleDocumentTaggingClient implements DocumentTaggingClient {

    private final OpenAiCompatibleDocumentTaggingAssistant assistant;

    public OpenAiCompatibleDocumentTaggingClient(ChatModel chatModel) {
        Objects.requireNonNull(chatModel, "文档标签聊天模型不能为空");
        // 无聊天记忆的 AI Service：每次标签运行独立调用，结构由返回类型约束
        this.assistant = AiServices.builder(OpenAiCompatibleDocumentTaggingAssistant.class)
                .chatModel(chatModel)
                .build();
    }

    /**
     * 对服务端限定的当前文档生成结构化摘要与标签候选。
     *
     * @param request 当前来源资料、可引用分片和版本快照
     * @return document-tag-v1 结构化标签结果
     */
    @Override
    public DocumentTaggingResult tag(DocumentTaggingRequest request) {
        Objects.requireNonNull(request, "文档标签请求不能为空");
        DocumentTaggingResult result = assistant.tag(buildTaggingContext(request));
        if (result == null) {
            throw new IllegalStateException("文档标签模型返回了空结果");
        }
        return result;
    }

    /**
     * 把当前文档与可引用分片渲染为确定性的用户上下文。
     *
     * @param request 文档标签请求
     * @return 模型用户消息内容
     */
    private String buildTaggingContext(DocumentTaggingRequest request) {
        StringBuilder context = new StringBuilder();
        context.append("## 当前文档\n");
        DocumentTaggingDocumentContext document = request.document();
        context.append("- documentId：").append(document.documentId()).append('\n');
        context.append("- 名称：").append(document.name()).append('\n');
        context.append("- 文件格式：").append(document.kind()).append('\n');
        context.append("- 业务类型：").append(document.documentType()).append('\n');
        context.append("- 可引用分片（quote 必须逐字出自以下分片）：\n");
        List<DocumentChunk> chunks = document.chunks();
        if (chunks.isEmpty()) {
            context.append("  （无分片，无法生成标签）\n");
            return context.toString();
        }
        for (DocumentChunk chunk : chunks) {
            context.append("  - 分片标识：").append(chunk.chunkId())
                    .append("；章节路径：").append(chunk.sectionPath()).append('\n');
            context.append("    原文：").append(chunk.contentText()).append('\n');
        }
        return context.toString();
    }
}

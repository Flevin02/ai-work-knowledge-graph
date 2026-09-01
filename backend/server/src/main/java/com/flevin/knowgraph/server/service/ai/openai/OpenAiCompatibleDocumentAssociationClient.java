package com.flevin.knowgraph.server.service.ai.openai;

import com.flevin.knowgraph.server.model.association.DocumentAssociationCandidateContext;
import com.flevin.knowgraph.server.model.association.DocumentAssociationDocumentContext;
import com.flevin.knowgraph.server.model.association.DocumentAssociationRequest;
import com.flevin.knowgraph.server.model.association.DocumentAssociationResult;
import com.flevin.knowgraph.server.model.ai.rag.DocumentChunk;
import com.flevin.knowgraph.server.service.association.DocumentAssociationClient;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;

import java.util.List;
import java.util.Objects;

/**
 * OpenAI-compatible 文档关联判断适配器，把 LangChain4j AI Service 隔离在领域接口之外。
 *
 * <p>服务端 Pipeline 已完成候选集合校验、有限分片组装和证据反查；本类只负责把安全上下文
 * 渲染为模型输入、调用结构化关联判断，并校验返回结果与候选集合一一对应。模型输出不构成
 * 关系事实，正式关系仍必须经过服务端校验和人工审核。</p>
 */
public class OpenAiCompatibleDocumentAssociationClient implements DocumentAssociationClient {

    private final OpenAiCompatibleDocumentAssociationAssistant assistant;

    public OpenAiCompatibleDocumentAssociationClient(ChatModel chatModel) {
        Objects.requireNonNull(chatModel, "关联判断聊天模型不能为空");
        // 无聊天记忆的 AI Service：每次关联运行独立调用，结构由返回类型约束
        this.assistant = AiServices.builder(OpenAiCompatibleDocumentAssociationAssistant.class)
                .chatModel(chatModel)
                .build();
    }

    /**
     * 对服务端候选集合逐一判断文档关系。
     *
     * @param request 当前文档、候选文档、可引用分片和版本快照
     * @return 与候选集合一一对应的结构化关系判断
     */
    @Override
    public DocumentAssociationResult associate(DocumentAssociationRequest request) {
        Objects.requireNonNull(request, "文档关联判断请求不能为空");

        // 在产生远程调用前校验候选集合非空；空候选在 Pipeline 中已被短路处理
        if (request.candidates().isEmpty()) {
            throw new IllegalArgumentException("文档关联判断候选集合不能为空");
        }

        DocumentAssociationResult result = assistant.associate(buildAssociationContext(request));

        // 模型必须对每个候选给出唯一判断，缺失或多余都会破坏一一对应契约
        if (result == null || result.decisions() == null
                || result.decisions().size() != request.candidates().size()) {
            throw new IllegalStateException(
                    "文档关联判断数量与候选集合不一致: 期望 "
                            + request.candidates().size()
                            + "，实际 " + (result == null || result.decisions() == null
                            ? 0 : result.decisions().size()));
        }
        return result;
    }

    /**
     * 把当前文档、候选集合和可引用分片渲染为确定性的用户上下文。
     *
     * @param request 文档关联判断请求
     * @return 模型用户消息内容
     */
    private String buildAssociationContext(DocumentAssociationRequest request) {
        StringBuilder context = new StringBuilder();
        context.append("## 当前文档\n");
        appendDocumentContext(context, request.currentDocument());

        context.append("\n## 候选文档\n");
        List<DocumentAssociationCandidateContext> candidates = request.candidates();
        for (int index = 0; index < candidates.size(); index++) {
            DocumentAssociationCandidateContext candidate = candidates.get(index);
            context.append("\n### 候选 ").append(index + 1)
                    .append("（documentId=").append(candidate.document().documentId()).append("）\n");
            context.append("命中通道：").append(String.join("、", candidate.matchedChannels())).append('\n');
            appendDocumentContext(context, candidate.document());
        }
        context.append("\n请对以上 ").append(candidates.size())
                .append(" 个候选逐一输出关系判断，数量与候选一一对应。\n");
        return context.toString();
    }

    /**
     * 渲染单份文档的名称、类型、摘要和可引用分片。
     *
     * @param context 输出缓冲
     * @param document 文档安全上下文
     */
    private void appendDocumentContext(
            StringBuilder context,
            DocumentAssociationDocumentContext document
    ) {
        context.append("- documentId：").append(document.documentId()).append('\n');
        context.append("- 名称：").append(document.name()).append('\n');
        context.append("- 业务类型：").append(document.documentType()).append('\n');
        if (document.summary() != null && !document.summary().isBlank()) {
            context.append("- 摘要：").append(document.summary()).append('\n');
        }
        context.append("- 可引用分片（quote 必须逐字出自以下分片）：\n");
        List<DocumentChunk> chunks = document.chunks();
        if (chunks.isEmpty()) {
            context.append("  （无分片，对该文档的判断只能输出 none）\n");
            return;
        }
        for (DocumentChunk chunk : chunks) {
            context.append("  - 分片标识：").append(chunk.chunkId())
                    .append("；章节路径：").append(chunk.sectionPath()).append('\n');
            context.append("    原文：").append(chunk.contentText()).append('\n');
        }
    }
}

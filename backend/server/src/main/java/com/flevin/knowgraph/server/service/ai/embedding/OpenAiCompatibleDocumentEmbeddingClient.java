package com.flevin.knowgraph.server.service.ai.embedding;

import com.flevin.knowgraph.server.model.ai.embedding.EmbeddingModelDescriptor;
import com.flevin.knowgraph.server.model.ai.embedding.EmbeddingVector;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * OpenAI-compatible 真实 Embedding 客户端，包装 LangChain4j {@link EmbeddingModel}。
 *
 * <p>该实现只在显式启用 AI 和 Embedding 时由 Spring 装配，业务层仍只依赖
 * {@link DocumentEmbeddingClient} 领域抽象。向量维度和实验版本来自显式配置，
 * 返回结果必须逐条通过数量、维度和有限值校验后才能进入可重建向量事实；
 * 客户端本身不持久化任何事实，也不决定候选排序。</p>
 */
public final class OpenAiCompatibleDocumentEmbeddingClient implements DocumentEmbeddingClient {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingModelDescriptor descriptor;

    /**
     * 创建使用显式模型描述的真实 Embedding 客户端。
     *
     * @param embeddingModel LangChain4j OpenAI-compatible Embedding 模型
     * @param descriptor 供应商、模型、版本和维度的显式描述
     */
    public OpenAiCompatibleDocumentEmbeddingClient(
            EmbeddingModel embeddingModel,
            EmbeddingModelDescriptor descriptor
    ) {
        this.embeddingModel = Objects.requireNonNull(embeddingModel, "Embedding 模型不能为空");
        this.descriptor = Objects.requireNonNull(descriptor, "Embedding 模型描述不能为空");
    }

    /**
     * 获取本次向量化使用的模型和版本快照。
     *
     * @return 可写入索引事实的模型描述
     */
    @Override
    public EmbeddingModelDescriptor descriptor() {
        return descriptor;
    }

    /**
     * 按输入顺序批量生成 Embedding 向量，并在写入前完成整批协议校验。
     *
     * @param texts 待向量化的非空文本列表
     * @return 与输入一一对应且维度一致的向量列表
     */
    @Override
    public List<EmbeddingVector> embed(List<String> texts) {
        Objects.requireNonNull(texts, "Embedding 文本列表不能为空");
        if (texts.isEmpty()) {
            throw new IllegalArgumentException("Embedding 文本列表不能为空批次");
        }

        // 与 Fake 客户端保持一致的输入约束：空白文本不产生无法追溯的向量
        for (int index = 0; index < texts.size(); index++) {
            if (texts.get(index) == null || texts.get(index).isBlank()) {
                throw new IllegalArgumentException("Embedding 文本不能为空，序号: " + (index + 1));
            }
        }

        // 按输入顺序批量调用真实端点，一次请求携带全部分片文本
        List<Embedding> embeddings = embeddingModel.embedAll(toSegments(texts)).content();
        if (embeddings == null || embeddings.size() != texts.size()) {
            throw new IllegalStateException(
                    "Embedding 返回数量与输入文本数量不一致: 期望 "
                            + texts.size() + "，实际 " + (embeddings == null ? 0 : embeddings.size()));
        }

        // 在任何持久化前逐条校验维度和有限值，防止脏向量进入可重建事实
        List<EmbeddingVector> vectors = new ArrayList<>(embeddings.size());
        for (int index = 0; index < embeddings.size(); index++) {
            Embedding embedding = embeddings.get(index);
            if (embedding == null || embedding.vector() == null) {
                throw new IllegalStateException("Embedding 返回了空向量，序号: " + (index + 1));
            }
            if (embedding.dimension() != descriptor.dimension()) {
                throw new IllegalStateException(
                        "Embedding 向量维度与配置描述不一致，序号: " + (index + 1)
                                + "，期望 " + descriptor.dimension()
                                + "，实际 " + embedding.dimension()
                                + "；请检查 AI_EMBEDDING_DIMENSION 或更换模型后升级 AI_EMBEDDING_VERSION");
            }
            vectors.add(new EmbeddingVector(embedding.vector()));
        }
        return List.copyOf(vectors);
    }

    /**
     * 将原文文本转换为 LangChain4j 分段，保持与输入一一对应。
     *
     * @param texts 非空原文文本
     * @return 文本分段列表
     */
    private List<TextSegment> toSegments(List<String> texts) {
        return texts.stream()
                .map(TextSegment::from)
                .toList();
    }
}

package com.flevin.knowgraph.server.service.ai.embedding;

import com.flevin.knowgraph.server.model.ai.embedding.EmbeddingModelDescriptor;
import com.flevin.knowgraph.server.model.ai.embedding.EmbeddingVector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 不调用外部模型的确定性 Embedding 实现，用于固定资料实验和自动回归。
 *
 * <p>该实现只把规范化文本的字符哈希累加到固定维度并归一化，能够验证数据流、版本隔离和排序逻辑，
 * 不能代表真实模型的语义质量。</p>
 */
public final class DeterministicFakeEmbeddingClient implements DocumentEmbeddingClient {

    private final EmbeddingModelDescriptor descriptor;

    /**
     * 创建默认 8 维 Fake Embedding 模型。
     */
    public DeterministicFakeEmbeddingClient() {
        this(8);
    }

    /**
     * 创建指定维度的 Fake Embedding 模型。
     *
     * @param dimension 向量维度
     */
    public DeterministicFakeEmbeddingClient(int dimension) {
        this.descriptor = new EmbeddingModelDescriptor(
                "fake",
                "deterministic-char-hash",
                "fake-embedding-v1",
                dimension
        );
    }

    @Override
    public EmbeddingModelDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public List<EmbeddingVector> embed(List<String> texts) {
        Objects.requireNonNull(texts, "Embedding 文本列表不能为空");
        List<EmbeddingVector> vectors = new ArrayList<>(texts.size());
        for (String text : texts) {
            vectors.add(embedOne(text));
        }
        return List.copyOf(vectors);
    }

    private EmbeddingVector embedOne(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Embedding 文本不能为空");
        }
        float[] values = new float[descriptor.dimension()];
        String normalized = text.strip().toLowerCase(Locale.ROOT);
        for (int index = 0; index < normalized.length(); index++) {
            int codePoint = normalized.charAt(index);
            int bucket = Math.floorMod(codePoint * 31 + index, values.length);
            values[bucket] += 1.0F + (codePoint % 17) / 100.0F;
        }
        normalize(values);
        return new EmbeddingVector(values);
    }

    private void normalize(float[] values) {
        double norm = 0D;
        for (float value : values) {
            norm += value * value;
        }
        if (norm == 0D) {
            values[0] = 1.0F;
            return;
        }
        float length = (float) Math.sqrt(norm);
        for (int index = 0; index < values.length; index++) {
            values[index] = values[index] / length;
        }
    }
}

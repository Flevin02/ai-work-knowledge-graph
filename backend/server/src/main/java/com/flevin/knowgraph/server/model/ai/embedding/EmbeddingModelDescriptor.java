package com.flevin.knowgraph.server.model.ai.embedding;

/**
 * Embedding 模型的可追溯描述，用于隔离不同供应商、模型、版本和向量维度。
 *
 * @param provider Embedding 供应商或 Fake 标识
 * @param model 模型名称
 * @param version 模型或实验版本
 * @param dimension 向量维度
 */
public record EmbeddingModelDescriptor(
        String provider,
        String model,
        String version,
        int dimension
) {

    public EmbeddingModelDescriptor {
        if (isBlank(provider) || isBlank(model) || isBlank(version)) {
            throw new IllegalArgumentException("Embedding 供应商、模型和版本不能为空");
        }
        if (dimension <= 0) {
            throw new IllegalArgumentException("Embedding 维度必须大于 0");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

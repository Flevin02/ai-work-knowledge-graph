package com.flevin.knowgraph.server.model.ai.embedding;

import java.util.Objects;

/**
 * 单条 Embedding 向量，构造时拒绝空向量、NaN 和无穷值。
 *
 * @param values 向量分量；访问时返回副本，避免调用方篡改已校验事实
 */
public record EmbeddingVector(float[] values) {

    public EmbeddingVector {
        Objects.requireNonNull(values, "Embedding 向量不能为空");
        if (values.length == 0) {
            throw new IllegalArgumentException("Embedding 向量维度必须大于 0");
        }
        values = values.clone();
        for (float value : values) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("Embedding 向量只能包含有限值");
            }
        }
    }

    @Override
    public float[] values() {
        return values.clone();
    }

    /**
     * 获取向量维度。
     *
     * @return 分量数量
     */
    public int dimension() {
        return values.length;
    }
}

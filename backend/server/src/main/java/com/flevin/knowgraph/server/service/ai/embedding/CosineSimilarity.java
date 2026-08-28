package com.flevin.knowgraph.server.service.ai.embedding;

import com.flevin.knowgraph.server.model.ai.embedding.EmbeddingVector;

/**
 * Java 精确余弦相似度计算，作为当前小规模分片实验的可解释检索基础。
 */
public final class CosineSimilarity {

    private CosineSimilarity() {
    }

    /**
     * 计算两条同维、有限向量的余弦相似度。
     *
     * @param left 左侧向量
     * @param right 右侧向量
     * @return [-1, 1] 范围内的余弦相似度
     */
    public static double score(EmbeddingVector left, EmbeddingVector right) {
        if (left == null || right == null) {
            throw new IllegalArgumentException("余弦相似度输入向量不能为空");
        }
        if (left.dimension() != right.dimension()) {
            throw new IllegalArgumentException("余弦相似度要求向量维度一致");
        }
        float[] leftValues = left.values();
        float[] rightValues = right.values();
        double dot = 0D;
        double leftNorm = 0D;
        double rightNorm = 0D;
        for (int index = 0; index < leftValues.length; index++) {
            dot += leftValues[index] * rightValues[index];
            leftNorm += leftValues[index] * leftValues[index];
            rightNorm += rightValues[index] * rightValues[index];
        }
        if (leftNorm == 0D || rightNorm == 0D) {
            return 0D;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }
}

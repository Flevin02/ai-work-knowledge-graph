package com.flevin.knowgraph.server.service.ai.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flevin.knowgraph.server.model.ai.embedding.EmbeddingModelDescriptor;
import com.flevin.knowgraph.server.model.ai.embedding.EmbeddingVector;
import com.flevin.knowgraph.server.model.ai.embedding.SemanticChunkVector;
import com.flevin.knowgraph.server.model.ai.rag.DocumentChunkIndexStateFact;
import com.flevin.knowgraph.server.repository.mapping.PersistenceMappingSupport;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Embedding 模型边界和向量 JSON 持久化转换单元测试。
 */
class EmbeddingPersistenceMappingTests {

    @Test
    void roundTripsVectorJsonAndKeepsDefensiveCopies() {
        PersistenceMappingSupport mappingSupport = new PersistenceMappingSupport(new ObjectMapper());
        EmbeddingVector source = new EmbeddingVector(new float[]{0.25F, -0.5F});

        // 将有限向量序列化为 MySQL 可保存的 JSON 数组
        String json = mappingSupport.embeddingVectorToJson(source);
        // 从 JSON 恢复向量并重新执行边界校验
        EmbeddingVector restored = mappingSupport.jsonToEmbeddingVector(json);

        assertThat(json).isEqualTo("[0.25,-0.5]");
        assertThat(restored.values()).containsExactly(0.25F, -0.5F);

        // 修改访问器返回数组，验证不会篡改已校验向量事实
        float[] exposed = restored.values();
        exposed[0] = 9F;
        assertThat(restored.values()).containsExactly(0.25F, -0.5F);
    }

    @Test
    void rejectsInvalidDescriptorVectorAndChunkVersionBoundaries() {
        // 空模型版本无法形成可重建索引边界
        assertThatThrownBy(() -> new EmbeddingModelDescriptor("fake", "model", " ", 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("版本");
        // 非有限分量不能写入向量事实
        assertThatThrownBy(() -> new EmbeddingVector(new float[]{1F, Float.POSITIVE_INFINITY}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("有限值");
        // 分片向量维度必须与模型描述一致
        assertThatThrownBy(() -> new SemanticChunkVector(
                101L,
                201L,
                301L,
                "chunk-1",
                "hash-1",
                "chunk-v1",
                new EmbeddingModelDescriptor("fake", "model", "v1", 2),
                new EmbeddingVector(new float[]{1F, 0F, 0F})
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("维度");
        // 空分片版本不能进入精确召回事实
        assertThatThrownBy(() -> new SemanticChunkVector(
                101L,
                201L,
                301L,
                "chunk-1",
                "hash-1",
                " ",
                new EmbeddingModelDescriptor("fake", "model", "v1", 2),
                new EmbeddingVector(new float[]{1F, 0F})
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("策略版本");

        // 未知索引状态不能进入可重建向量事实
        assertThatThrownBy(() -> new DocumentChunkIndexStateFact(
                1L,
                2L,
                3L,
                4L,
                "chunk-1",
                "hash-1",
                "chunk-v1",
                "fake",
                "model",
                "v1",
                2,
                new EmbeddingVector(new float[]{1F, 0F}),
                "unknown",
                null,
                Instant.parse("2026-08-28T00:00:00Z"),
                Instant.parse("2026-08-28T00:00:00Z")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("状态");
    }
}

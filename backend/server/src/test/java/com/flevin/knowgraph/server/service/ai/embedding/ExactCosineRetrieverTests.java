package com.flevin.knowgraph.server.service.ai.embedding;

import com.flevin.knowgraph.server.model.ai.embedding.EmbeddingModelDescriptor;
import com.flevin.knowgraph.server.model.ai.embedding.EmbeddingVector;
import com.flevin.knowgraph.server.model.ai.embedding.SemanticChunkCandidate;
import com.flevin.knowgraph.server.model.ai.embedding.SemanticChunkVector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MySQL 向量事实重建前的精确 COSINE 检索单元测试。
 *
 * <p>测试使用完全虚构的向量，不证明真实 Embedding 语义质量。</p>
 */
class ExactCosineRetrieverTests {

    private static final Long SPACE_ID = 101L;
    private static final String CHUNK_VERSION = "prd-markdown-section-v1+section-aware-v1:max-1500:overlap-150";
    private static final EmbeddingModelDescriptor DESCRIPTOR = new EmbeddingModelDescriptor(
            "fake",
            "test-model",
            "v1",
            2
    );

    @Test
    void filtersSpaceModelVersionAndSubjectBeforeReturningStableTopK() {
        ExactCosineRetriever retriever = new ExactCosineRetriever();
        List<SemanticChunkCandidate> candidates = retriever.retrieve(
                SPACE_ID,
                900L,
                CHUNK_VERSION,
                DESCRIPTOR,
                vector(1F, 0F),
                List.of(
                        chunk(900L, 1L, 1F, 0F),
                        chunk(901L, 2L, 0.9F, 0.1F),
                        chunk(902L, 3L, 0F, 1F),
                        new SemanticChunkVector(
                                999L,
                                904L,
                                4L,
                                "chunk-4",
                                "hash-4",
                                CHUNK_VERSION,
                                DESCRIPTOR,
                                vector(1F, 0F)
                        ),
                        new SemanticChunkVector(
                                SPACE_ID,
                                905L,
                                5L,
                                "chunk-5",
                                "hash-5",
                                CHUNK_VERSION,
                                new EmbeddingModelDescriptor("fake", "test-model", "v2", 2),
                                vector(1F, 0F)
                        ),
                        new SemanticChunkVector(
                                SPACE_ID,
                                906L,
                                6L,
                                "chunk-6",
                                "hash-6",
                                "legacy-chunk-version",
                                DESCRIPTOR,
                                vector(1F, 0F)
                        )
                ),
                2
        );

        assertThat(candidates).extracting(SemanticChunkCandidate::sourceDocumentId)
                .containsExactly(901L, 902L);
        assertThat(candidates.get(0).score()).isGreaterThan(candidates.get(1).score());
    }

    @Test
    void usesStableIdentifiersWhenScoresAreEqual() {
        ExactCosineRetriever retriever = new ExactCosineRetriever();
        List<SemanticChunkCandidate> candidates = retriever.retrieve(
                SPACE_ID,
                null,
                CHUNK_VERSION,
                DESCRIPTOR,
                vector(1F, 0F),
                List.of(
                        chunk(202L, 20L, 1F, 0F),
                        chunk(201L, 10L, 1F, 0F)
                ),
                8
        );

        assertThat(candidates).extracting(SemanticChunkCandidate::sourceDocumentId)
                .containsExactly(201L, 202L);
    }

    @Test
    void rejectsDimensionMismatchAndInvalidTopK() {
        ExactCosineRetriever retriever = new ExactCosineRetriever();

        assertThatThrownBy(() -> retriever.retrieve(
                SPACE_ID,
                null,
                CHUNK_VERSION,
                DESCRIPTOR,
                new EmbeddingVector(new float[]{1F, 0F, 0F}),
                List.of(),
                8
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("维度");

        assertThatThrownBy(() -> retriever.retrieve(
                SPACE_ID,
                null,
                CHUNK_VERSION,
                DESCRIPTOR,
                vector(1F, 0F),
                List.of(),
                0
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TopK");
    }

    @Test
    void fakeEmbeddingIsDeterministicAndReturnsFiniteVectors() {
        DeterministicFakeEmbeddingClient client = new DeterministicFakeEmbeddingClient(8);

        EmbeddingVector first = client.embed(List.of("虚构项目需要证据")).get(0);
        EmbeddingVector second = client.embed(List.of("虚构项目需要证据")).get(0);

        assertThat(first.values()).containsExactly(second.values());
        assertThat(first.dimension()).isEqualTo(8);
        for (float value : first.values()) {
            assertThat(Float.isFinite(value)).isTrue();
        }
    }

    @Test
    void rejectsNonFiniteVectorValuesAtBoundary() {
        assertThatThrownBy(() -> new EmbeddingVector(new float[]{1F, Float.NaN}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("有限值");
    }

    private SemanticChunkVector chunk(
            Long sourceDocumentId,
            Long chunkRecordId,
            float first,
            float second
    ) {
        return new SemanticChunkVector(
                SPACE_ID,
                sourceDocumentId,
                chunkRecordId,
                "chunk-" + chunkRecordId,
                "hash-" + chunkRecordId,
                CHUNK_VERSION,
                DESCRIPTOR,
                vector(first, second)
        );
    }

    private EmbeddingVector vector(float first, float second) {
        return new EmbeddingVector(new float[]{first, second});
    }
}

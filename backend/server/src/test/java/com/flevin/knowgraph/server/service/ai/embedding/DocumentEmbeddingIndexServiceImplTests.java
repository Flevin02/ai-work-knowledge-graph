package com.flevin.knowgraph.server.service.ai.embedding;

import com.flevin.knowgraph.server.model.ai.embedding.DocumentEmbeddingIndexResult;
import com.flevin.knowgraph.server.model.ai.embedding.EmbeddingModelDescriptor;
import com.flevin.knowgraph.server.model.ai.embedding.EmbeddingVector;
import com.flevin.knowgraph.server.model.ai.rag.DocumentChunkFact;
import com.flevin.knowgraph.server.model.ai.rag.DocumentChunkIndexStateFact;
import com.flevin.knowgraph.server.repository.document.DocumentChunkIndexStateRepository;
import com.flevin.knowgraph.server.repository.document.DocumentChunkRepository;
import com.flevin.knowgraph.server.service.ai.rag.DocumentRagVersionResolver;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Fake Embedding 索引服务单元测试，不连接 MySQL 或真实模型端点。
 */
class DocumentEmbeddingIndexServiceImplTests {

    private static final Long SPACE_ID = 101L;
    private static final Long DOCUMENT_ID = 201L;
    private static final String CHUNK_VERSION =
            "prd-markdown-section-v1+section-aware-v1:max-1500:overlap-150";
    private static final EmbeddingModelDescriptor DESCRIPTOR = new EmbeddingModelDescriptor(
            "fake",
            "deterministic-char-hash",
            "fake-embedding-v1",
            2
    );

    @Test
    @SuppressWarnings("unchecked")
    void embedsOnlyMissingChunksAndWritesOneFullyValidatedBatch() {
        DocumentChunkRepository chunkRepository = mock(DocumentChunkRepository.class);
        DocumentChunkIndexStateRepository indexRepository = mock(DocumentChunkIndexStateRepository.class);
        DocumentRagVersionResolver versionResolver = mock(DocumentRagVersionResolver.class);
        DocumentEmbeddingClient embeddingClient = mock(DocumentEmbeddingClient.class);
        List<DocumentChunkFact> chunks = List.of(chunk(401L, "chunk-1"), chunk(402L, "chunk-2"));

        // 固定当前完整分片版本，模拟同一实验边界
        when(versionResolver.chunkVersion()).thenReturn(CHUNK_VERSION);
        // 返回两条已经持久化的当前分片事实
        when(chunkRepository.findByDocument(SPACE_ID, DOCUMENT_ID, CHUNK_VERSION)).thenReturn(chunks);
        // 返回 Fake 模型描述，限定供应商、模型、版本和维度
        when(embeddingClient.descriptor()).thenReturn(DESCRIPTOR);
        // 第一条分片已有兼容向量，第二条需要本次补建
        when(indexRepository.findReadyByDocument(
                SPACE_ID,
                DOCUMENT_ID,
                CHUNK_VERSION,
                DESCRIPTOR.provider(),
                DESCRIPTOR.model(),
                DESCRIPTOR.version(),
                DESCRIPTOR.dimension()
        )).thenReturn(List.of(ready(chunks.getFirst(), new EmbeddingVector(new float[]{1F, 0F}))));
        // Fake 客户端只接收缺失分片并返回同维有限向量
        when(embeddingClient.embed(List.of("虚构分片 chunk-2")))
                .thenReturn(List.of(new EmbeddingVector(new float[]{0F, 1F})));

        DocumentEmbeddingIndexServiceImpl service = new DocumentEmbeddingIndexServiceImpl(
                chunkRepository,
                indexRepository,
                versionResolver,
                embeddingClient
        );

        // 执行当前资料的缺失向量补建
        DocumentEmbeddingIndexResult result = service.indexDocument(SPACE_ID, DOCUMENT_ID);

        assertThat(result.totalChunkCount()).isEqualTo(2);
        assertThat(result.reusedCount()).isEqualTo(1);
        assertThat(result.indexedCount()).isEqualTo(1);

        ArgumentCaptor<List<DocumentChunkIndexStateFact>> factsCaptor = ArgumentCaptor.forClass(List.class);
        // 捕获原子写入批次，验证只包含缺失分片
        verify(indexRepository).saveAll(factsCaptor.capture());
        assertThat(factsCaptor.getValue()).singleElement().satisfies(fact -> {
            assertThat(fact.chunkRecordId()).isEqualTo(402L);
            assertThat(fact.chunkVersion()).isEqualTo(CHUNK_VERSION);
            assertThat(fact.dimension()).isEqualTo(2);
            assertThat(fact.status()).isEqualTo("ready");
            assertThat(fact.vector().values()).containsExactly(0F, 1F);
        });
    }

    @Test
    void rejectsIncompleteOrWrongDimensionBatchBeforeAnyWrite() {
        DocumentChunkRepository chunkRepository = mock(DocumentChunkRepository.class);
        DocumentChunkIndexStateRepository indexRepository = mock(DocumentChunkIndexStateRepository.class);
        DocumentRagVersionResolver versionResolver = mock(DocumentRagVersionResolver.class);
        DocumentEmbeddingClient embeddingClient = mock(DocumentEmbeddingClient.class);
        List<DocumentChunkFact> chunks = List.of(chunk(401L, "chunk-1"), chunk(402L, "chunk-2"));

        // 准备当前分片和模型边界，两条分片均缺少向量
        when(versionResolver.chunkVersion()).thenReturn(CHUNK_VERSION);
        when(chunkRepository.findByDocument(SPACE_ID, DOCUMENT_ID, CHUNK_VERSION)).thenReturn(chunks);
        when(embeddingClient.descriptor()).thenReturn(DESCRIPTOR);
        when(indexRepository.findReadyByDocument(
                SPACE_ID,
                DOCUMENT_ID,
                CHUNK_VERSION,
                DESCRIPTOR.provider(),
                DESCRIPTOR.model(),
                DESCRIPTOR.version(),
                DESCRIPTOR.dimension()
        )).thenReturn(List.of());
        // 模拟供应商只返回一条向量，验证数量错误不会产生半批写入
        when(embeddingClient.embed(anyList()))
                .thenReturn(List.of(new EmbeddingVector(new float[]{1F, 0F})));

        DocumentEmbeddingIndexServiceImpl service = new DocumentEmbeddingIndexServiceImpl(
                chunkRepository,
                indexRepository,
                versionResolver,
                embeddingClient
        );

        // 返回数量与输入分片不一致时整批失败
        assertThatThrownBy(() -> service.indexDocument(SPACE_ID, DOCUMENT_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("数量");
        // 完整校验失败前禁止写入任何向量事实
        verify(indexRepository, never()).saveAll(anyList());

        // 改为返回两条三维向量，验证模型描述维度不匹配同样整批失败
        when(embeddingClient.embed(anyList())).thenReturn(List.of(
                new EmbeddingVector(new float[]{1F, 0F, 0F}),
                new EmbeddingVector(new float[]{0F, 1F, 0F})
        ));

        // 维度不一致时在数据库写入前失败
        assertThatThrownBy(() -> service.indexDocument(SPACE_ID, DOCUMENT_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("维度");
        // 两次失败均不能留下任何向量事实
        verify(indexRepository, never()).saveAll(anyList());
    }

    /**
     * 创建一条当前版本的虚构分片事实。
     *
     * @param id 分片事实标识
     * @param chunkId 文档内分片标识
     * @return 分片事实
     */
    private DocumentChunkFact chunk(Long id, String chunkId) {
        Instant timestamp = Instant.parse("2026-08-27T08:00:00Z");
        return new DocumentChunkFact(
                id,
                SPACE_ID,
                DOCUMENT_ID,
                301L,
                "section-1",
                chunkId,
                "prd-markdown-section-v1",
                "用户中心",
                id.equals(401L) ? 1 : 2,
                id.equals(401L) ? 1 : 2,
                "虚构分片 " + chunkId,
                id.equals(401L) ? 0 : 20,
                id.equals(401L) ? 18 : 38,
                "hash-" + chunkId,
                CHUNK_VERSION,
                timestamp,
                timestamp
        );
    }

    /**
     * 创建一条已就绪向量事实。
     *
     * @param chunk 对应分片事实
     * @param vector 已校验向量
     * @return 已就绪索引状态
     */
    private DocumentChunkIndexStateFact ready(
            DocumentChunkFact chunk,
            EmbeddingVector vector
    ) {
        Instant timestamp = Instant.parse("2026-08-27T08:00:00Z");
        return new DocumentChunkIndexStateFact(
                501L,
                SPACE_ID,
                DOCUMENT_ID,
                chunk.id(),
                chunk.chunkId(),
                chunk.contentHash(),
                CHUNK_VERSION,
                DESCRIPTOR.provider(),
                DESCRIPTOR.model(),
                DESCRIPTOR.version(),
                DESCRIPTOR.dimension(),
                vector,
                "ready",
                null,
                timestamp,
                timestamp
        );
    }
}

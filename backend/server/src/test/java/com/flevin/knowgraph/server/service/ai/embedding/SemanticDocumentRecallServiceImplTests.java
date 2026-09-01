package com.flevin.knowgraph.server.service.ai.embedding;

import com.flevin.knowgraph.server.model.ai.embedding.DocumentEmbeddingIndexResult;
import com.flevin.knowgraph.server.model.ai.embedding.EmbeddingModelDescriptor;
import com.flevin.knowgraph.server.model.ai.embedding.EmbeddingVector;
import com.flevin.knowgraph.server.model.ai.embedding.SemanticDocumentCandidate;
import com.flevin.knowgraph.server.model.ai.embedding.SemanticDocumentRecall;
import com.flevin.knowgraph.server.model.ai.rag.DocumentChunk;
import com.flevin.knowgraph.server.model.ai.rag.DocumentChunkIndexStateFact;
import com.flevin.knowgraph.server.model.ai.rag.DocumentSection;
import com.flevin.knowgraph.server.model.document.SourceDocument;
import com.flevin.knowgraph.server.model.document.SourceDocumentType;
import com.flevin.knowgraph.server.repository.document.DocumentChunkIndexStateRepository;
import com.flevin.knowgraph.server.repository.document.SourceDocumentRepository;
import com.flevin.knowgraph.server.service.ai.rag.DocumentRagVersionResolver;
import com.flevin.knowgraph.server.service.ai.rag.DocumentStructurePersistenceService;
import com.flevin.knowgraph.server.service.ai.rag.PrdMarkdownSectionParser;
import com.flevin.knowgraph.server.service.ai.rag.SectionAwareDocumentChunker;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 独立文档语义召回服务单元测试，不连接 MySQL 或外部模型端点。
 */
class SemanticDocumentRecallServiceImplTests {

    private static final Long SPACE_ID = 101L;
    private static final Long SUBJECT_ID = 201L;
    private static final String CHUNK_VERSION =
            "prd-markdown-section-v1+section-aware-v1:max-1500:overlap-150";
    private static final EmbeddingModelDescriptor DESCRIPTOR = new EmbeddingModelDescriptor(
            "fake",
            "deterministic-char-hash",
            "fake-embedding-v1",
            2
    );

    @Test
    void excludesSubjectAndMaxPoolsChunkScoresPerDocument() {
        SemanticDocumentRecallServiceImpl service = mockDependencies(List.of(
                        // 主体查询向量 q1、q2
                        readyVector(SUBJECT_ID, 11L, "s-1", 1F, 0F),
                        readyVector(SUBJECT_ID, 12L, "s-2", 0F, 1F),
                        // 目标文档 202：与 q1 完全同向的分片给出 1.0 的最高分
                        readyVector(202L, 21L, "t-a", 0.6F, 0.8F),
                        readyVector(202L, 22L, "t-b", 1F, 0F),
                        // 目标文档 203：最高只达到 0.8
                        readyVector(203L, 31L, "u-c", 0.6F, 0.8F)
                ));

        SemanticDocumentRecall recall = service.recall(SPACE_ID, SUBJECT_ID);

        assertThat(recall.semanticRecallPolicyVersion())
                .isEqualTo("document-semantic-recall-v1");
        assertThat(recall.chunkVersion()).isEqualTo(CHUNK_VERSION);
        assertThat(recall.descriptor()).isEqualTo(DESCRIPTOR);
        assertThat(recall.topK()).isEqualTo(8);
        assertThat(recall.queryChunkCount()).isEqualTo(2);

        // 文档级候选按 max 池化分数降序排列
        assertThat(recall.candidates()).hasSize(2);
        assertThat(recall.candidates().get(0).sourceDocumentId()).isEqualTo(202L);
        assertThat(recall.candidates().get(0).bestChunkScore()).isCloseTo(1.0D, offset(1e-6D));
        assertThat(recall.candidates().get(0).bestChunkId()).isEqualTo("t-b");
        assertThat(recall.candidates().get(0).rank()).isEqualTo(1);
        assertThat(recall.candidates().get(1).sourceDocumentId()).isEqualTo(203L);
        assertThat(recall.candidates().get(1).bestChunkScore()).isCloseTo(0.8D, offset(1e-6D));
        assertThat(recall.candidates().get(1).bestChunkId()).isEqualTo("u-c");
        assertThat(recall.candidates().get(1).rank()).isEqualTo(2);

        // 文档级候选永不包含主体自身
        assertThat(recall.candidates())
                .extracting(SemanticDocumentCandidate::sourceDocumentId)
                .doesNotContain(SUBJECT_ID);
    }

    @Test
    void returnsEmptyRecallWhenSpaceHasNoReadyVectors() {
        SemanticDocumentRecallServiceImpl service = mockDependencies(List.of());

        SemanticDocumentRecall recall = service.recall(SPACE_ID, SUBJECT_ID);

        // 空索引属于正常空态，不产生候选也不抛出系统失败
        assertThat(recall.candidates()).isEmpty();
        assertThat(recall.queryChunkCount()).isZero();
        assertThat(recall.descriptor()).isEqualTo(DESCRIPTOR);
    }

    @Test
    void returnsEmptyRecallWithoutPersistingWhenDocumentHasNoChunks() {
        SourceDocumentRepository sourceDocumentRepository = mock(SourceDocumentRepository.class);
        PrdMarkdownSectionParser sectionParser = mock(PrdMarkdownSectionParser.class);
        SectionAwareDocumentChunker documentChunker = mock(SectionAwareDocumentChunker.class);
        DocumentStructurePersistenceService structurePersistenceService =
                mock(DocumentStructurePersistenceService.class);
        DocumentEmbeddingIndexService embeddingIndexService = mock(DocumentEmbeddingIndexService.class);
        DocumentChunkIndexStateRepository indexStateRepository = mock(DocumentChunkIndexStateRepository.class);
        DocumentRagVersionResolver versionResolver = mock(DocumentRagVersionResolver.class);

        SourceDocument document = sourceDocument("空白原文");
        when(sourceDocumentRepository.findById(SPACE_ID, SUBJECT_ID))
                .thenReturn(Optional.of(document));
        when(versionResolver.chunkVersion()).thenReturn(CHUNK_VERSION);
        when(sectionParser.parse("空白原文")).thenReturn(List.of());
        when(documentChunker.chunk(List.of())).thenReturn(List.of());
        when(embeddingIndexService.indexDocument(SPACE_ID, SUBJECT_ID))
                .thenReturn(new DocumentEmbeddingIndexResult(DESCRIPTOR, CHUNK_VERSION, 0, 0, 0));

        SemanticDocumentRecallServiceImpl service = new SemanticDocumentRecallServiceImpl(
                sourceDocumentRepository,
                sectionParser,
                documentChunker,
                structurePersistenceService,
                embeddingIndexService,
                indexStateRepository,
                versionResolver
        );

        SemanticDocumentRecall recall = service.recall(SPACE_ID, SUBJECT_ID);

        // 空白原文没有可追溯章节，直接空态返回且不写入任何事实
        assertThat(recall.candidates()).isEmpty();
        assertThat(recall.queryChunkCount()).isZero();
        verify(structurePersistenceService, never()).persist(any(), anyList(), anyList());
        verify(indexStateRepository, never()).findReady(
                anyLong(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void rejectsNonPositiveSpaceOrDocumentIdentifiers() {
        SemanticDocumentRecallServiceImpl service = mockDependencies(List.of());

        assertThatThrownBy(() -> service.recall(0L, SUBJECT_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("大于零");
        assertThatThrownBy(() -> service.recall(SPACE_ID, -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("大于零");
    }

    /**
     * 创建带常用 mock 依赖的服务，来源资料和分片解析返回固定非空结果。
     *
     * @param readyVectors 已就绪向量索引事实
     * @return 待测服务
     */
    private SemanticDocumentRecallServiceImpl mockDependencies(
            List<DocumentChunkIndexStateFact> readyVectors
    ) {
        SourceDocumentRepository sourceDocumentRepository = mock(SourceDocumentRepository.class);
        PrdMarkdownSectionParser sectionParser = mock(PrdMarkdownSectionParser.class);
        SectionAwareDocumentChunker documentChunker = mock(SectionAwareDocumentChunker.class);
        DocumentStructurePersistenceService structurePersistenceService =
                mock(DocumentStructurePersistenceService.class);
        DocumentEmbeddingIndexService embeddingIndexService = mock(DocumentEmbeddingIndexService.class);
        DocumentChunkIndexStateRepository indexStateRepository = mock(DocumentChunkIndexStateRepository.class);
        DocumentRagVersionResolver versionResolver = mock(DocumentRagVersionResolver.class);

        SourceDocument document = sourceDocument("虚构主体原文");
        when(sourceDocumentRepository.findById(SPACE_ID, SUBJECT_ID))
                .thenReturn(Optional.of(document));
        when(versionResolver.chunkVersion()).thenReturn(CHUNK_VERSION);
        when(sectionParser.parse("虚构主体原文")).thenReturn(List.of(
                new DocumentSection(
                        "section-1",
                        "概述",
                        1,
                        "概述",
                        1,
                        "虚构主体原文",
                        0,
                        6
                )
        ));
        when(documentChunker.chunk(anyList())).thenReturn(List.of(
                new DocumentChunk(
                        "s-1",
                        "section-1",
                        "概述",
                        1,
                        "虚构主体原文",
                        0,
                        6
                )
        ));
        when(embeddingIndexService.indexDocument(SPACE_ID, SUBJECT_ID))
                .thenReturn(new DocumentEmbeddingIndexResult(DESCRIPTOR, CHUNK_VERSION, 1, 0, 1));
        when(indexStateRepository.findReady(
                SPACE_ID,
                CHUNK_VERSION,
                DESCRIPTOR.provider(),
                DESCRIPTOR.model(),
                DESCRIPTOR.version(),
                DESCRIPTOR.dimension()
        )).thenReturn(readyVectors);

        return new SemanticDocumentRecallServiceImpl(
                sourceDocumentRepository,
                sectionParser,
                documentChunker,
                structurePersistenceService,
                embeddingIndexService,
                indexStateRepository,
                versionResolver
        );
    }

    /**
     * 创建一条当前版本和模型边界下已就绪的虚构向量索引事实。
     *
     * @param sourceDocumentId 来源资料标识
     * @param chunkRecordId 分片事实标识
     * @param chunkId 文档内分片标识
     * @param x 向量第一分量
     * @param y 向量第二分量
     * @return 已就绪向量索引事实
     */
    private DocumentChunkIndexStateFact readyVector(
            Long sourceDocumentId,
            Long chunkRecordId,
            String chunkId,
            float x,
            float y
    ) {
        Instant timestamp = Instant.parse("2026-08-31T00:00:00Z");
        return new DocumentChunkIndexStateFact(
                chunkRecordId + 500,
                SPACE_ID,
                sourceDocumentId,
                chunkRecordId,
                chunkId,
                "hash-" + chunkId,
                CHUNK_VERSION,
                DESCRIPTOR.provider(),
                DESCRIPTOR.model(),
                DESCRIPTOR.version(),
                DESCRIPTOR.dimension(),
                new EmbeddingVector(new float[]{x, y}),
                "ready",
                null,
                timestamp,
                timestamp
        );
    }

    /**
     * 创建一条属于默认测试空间的来源资料。
     *
     * @param contentText 完整原文
     * @return 来源资料
     */
    private SourceDocument sourceDocument(String contentText) {
        Instant timestamp = Instant.parse("2026-08-31T00:00:00Z");
        return new SourceDocument(
                SUBJECT_ID,
                SPACE_ID,
                301L,
                "虚构主体.md",
                "markdown",
                SourceDocumentType.GENERAL,
                "hash-document",
                "uploads/虚构主体.md",
                contentText,
                "虚构主体原文",
                "active",
                18L,
                timestamp,
                timestamp
        );
    }
}

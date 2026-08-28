package com.flevin.knowgraph.server.service.ai.rag;

import com.flevin.knowgraph.server.config.properties.RagProperties;
import com.flevin.knowgraph.server.model.ai.rag.DocumentChunk;
import com.flevin.knowgraph.server.model.ai.rag.DocumentChunkFact;
import com.flevin.knowgraph.server.model.ai.rag.DocumentSection;
import com.flevin.knowgraph.server.model.ai.rag.DocumentSectionFact;
import com.flevin.knowgraph.server.model.ai.rag.PersistedDocumentStructure;
import com.flevin.knowgraph.server.model.document.SourceDocument;
import com.flevin.knowgraph.server.model.document.SourceDocumentType;
import com.flevin.knowgraph.server.repository.document.DocumentChunkRepository;
import com.flevin.knowgraph.server.repository.document.DocumentSectionRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 章节与分片事实幂等持久化单元测试，不连接 MySQL 或真实 Embedding 服务。
 */
class DocumentStructurePersistenceServiceImplTests {

    private static final Long SPACE_ID = 101L;
    private static final Long DOCUMENT_ID = 201L;

    @Test
    void persistsFactsOnceAndReusesDatabaseIdentifiersOnRepeatedExtraction() {
        DocumentSectionRepository sectionRepository = mock(DocumentSectionRepository.class);
        DocumentChunkRepository chunkRepository = mock(DocumentChunkRepository.class);
        List<DocumentSectionFact> savedSections = new ArrayList<>();
        List<DocumentChunkFact> savedChunks = new ArrayList<>();

        // 使用内存列表模拟同一解析版本的章节事实读取
        when(sectionRepository.findByDocument(
                SPACE_ID,
                DOCUMENT_ID,
                "prd-markdown-section-v1"
        )).thenAnswer(invocation -> List.copyOf(savedSections));
        // 保存章节时加入内存事实列表，模拟数据库首次插入
        doAnswer(invocation -> {
            savedSections.add(invocation.getArgument(0));
            return null;
        }).when(sectionRepository).save(any(DocumentSectionFact.class));

        String chunkVersion = "prd-markdown-section-v1+section-aware-v1:max-1500:overlap-150";
        // 使用内存列表模拟同一完整分片版本的事实读取
        when(chunkRepository.findByDocument(
                SPACE_ID,
                DOCUMENT_ID,
                chunkVersion
        )).thenAnswer(invocation -> List.copyOf(savedChunks));
        // 保存分片时加入内存事实列表，模拟数据库首次插入
        doAnswer(invocation -> {
            savedChunks.add(invocation.getArgument(0));
            return null;
        }).when(chunkRepository).save(any(DocumentChunkFact.class));

        RagProperties properties = new RagProperties();
        // 创建真实版本解析器，验证默认参数形成稳定完整版本
        DocumentRagVersionResolver versionResolver = new DocumentRagVersionResolver(properties);
        DocumentStructurePersistenceServiceImpl service = new DocumentStructurePersistenceServiceImpl(
                sectionRepository,
                chunkRepository,
                versionResolver
        );
        String content = "# 用户中心\n\n用户中心负责统一入口。\n\n## 登录功能\n\n登录功能支持验证码。";
        SourceDocument document = document(content);
        // 使用真实确定性解析器生成章节和原文偏移
        List<DocumentSection> sections = new PrdMarkdownSectionParser().parse(content);
        // 使用真实章节感知分片器生成分片和原文偏移
        List<DocumentChunk> chunks = new SectionAwareDocumentChunker(properties).chunk(sections);

        // 首次持久化生成章节和分片事实标识
        PersistedDocumentStructure first = service.persist(document, sections, chunks);
        // 重复持久化应复用相同内容和版本下的数据库事实
        PersistedDocumentStructure second = service.persist(document, sections, chunks);

        assertThat(first.parserVersion()).isEqualTo("prd-markdown-section-v1");
        assertThat(first.chunkVersion()).isEqualTo(chunkVersion);
        assertThat(first.sections()).hasSameSizeAs(sections);
        assertThat(first.chunks()).hasSameSizeAs(chunks);
        assertThat(second.sections()).extracting(DocumentSectionFact::id)
                .containsExactlyElementsOf(first.sections().stream().map(DocumentSectionFact::id).toList());
        assertThat(second.chunks()).extracting(DocumentChunkFact::id)
                .containsExactlyElementsOf(first.chunks().stream().map(DocumentChunkFact::id).toList());
        assertThat(first.chunks()).extracting(DocumentChunkFact::documentOrdinal)
                .containsExactly(1, 2);

        // 重复运行只读取并复用事实，不再次调用章节插入
        verify(sectionRepository, times(sections.size())).save(any(DocumentSectionFact.class));
        // 重复运行只读取并复用事实，不再次调用分片插入
        verify(chunkRepository, times(chunks.size())).save(any(DocumentChunkFact.class));
    }

    @Test
    void rejectsSlicesThatCannotBeTracedBackToSourceTextBeforeWriting() {
        DocumentSectionRepository sectionRepository = mock(DocumentSectionRepository.class);
        DocumentChunkRepository chunkRepository = mock(DocumentChunkRepository.class);
        DocumentStructurePersistenceServiceImpl service = new DocumentStructurePersistenceServiceImpl(
                sectionRepository,
                chunkRepository,
                new DocumentRagVersionResolver(new RagProperties())
        );
        SourceDocument document = document("# 标题\n真实正文");
        DocumentSection invalidSection = new DocumentSection(
                "section-1",
                "标题",
                1,
                "标题",
                1,
                "# 标题\n错误正文",
                0,
                document.contentText().length()
        );
        DocumentChunk invalidChunk = new DocumentChunk(
                "section-1-chunk-1",
                "section-1",
                "标题",
                1,
                "# 标题\n错误正文",
                0,
                document.contentText().length()
        );

        // 无法逐字反查的片段必须在任何数据库写入前失败
        assertThatThrownBy(() -> service.persist(
                document,
                List.of(invalidSection),
                List.of(invalidChunk)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("逐字反查");

        // 校验失败时不写入章节事实
        verify(sectionRepository, never()).save(any(DocumentSectionFact.class));
        // 校验失败时不写入分片事实
        verify(chunkRepository, never()).save(any(DocumentChunkFact.class));
    }

    /**
     * 创建一份虚构来源资料。
     *
     * @param content 来源原文
     * @return 测试来源资料
     */
    private SourceDocument document(String content) {
        Instant timestamp = Instant.parse("2026-08-27T08:00:00Z");
        return new SourceDocument(
                DOCUMENT_ID,
                SPACE_ID,
                301L,
                "虚构需求.md",
                "markdown",
                SourceDocumentType.PRD,
                "document-hash",
                "/tmp/fictional-document.md",
                content,
                content,
                "active",
                content.length(),
                timestamp,
                timestamp
        );
    }
}

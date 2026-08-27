package com.flevin.knowgraph.server.tag;

import com.flevin.knowgraph.server.KnowledgeGraphApplication;
import com.flevin.knowgraph.server.model.document.DocumentImportResponse;
import com.flevin.knowgraph.server.model.document.SourceDocument;
import com.flevin.knowgraph.server.model.tag.DocumentTagCandidate;
import com.flevin.knowgraph.server.model.tag.DocumentTagEvidenceCandidate;
import com.flevin.knowgraph.server.model.tag.DocumentTaggingRequest;
import com.flevin.knowgraph.server.model.tag.DocumentTaggingResult;
import com.flevin.knowgraph.server.model.tag.DocumentTaggingRunResponse;
import com.flevin.knowgraph.server.repository.document.SourceDocumentRepository;
import com.flevin.knowgraph.server.service.document.DocumentService;
import com.flevin.knowgraph.server.service.tag.DocumentTaggingClient;
import com.flevin.knowgraph.server.service.tag.DocumentTaggingService;
import com.flevin.knowgraph.server.support.TestKnowledgeSpaceFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 标签运行、Fake 候选、服务端校验、幂等物化和恢复集成测试。
 */
@SpringBootTest(classes = KnowledgeGraphApplication.class, properties = {
        "app.upload-dir=target/test-data/document-tagging-service-uploads",
        "test.document-tagging-client=service"
})
@Import(DocumentTaggingServiceIntegrationTests.FakeTaggingConfiguration.class)
class DocumentTaggingServiceIntegrationTests {

    private static final Long SPACE_ID = TestKnowledgeSpaceFixtures.DEFAULT_SPACE_ID;

    @Autowired
    private DocumentTaggingService taggingService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private SourceDocumentRepository sourceDocumentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FakeTaggingClient fakeTaggingClient;

    @BeforeEach
    void clearData() {
        // 按外键依赖顺序清理标签、文档关联和来源资料数据
        jdbcTemplate.update("DELETE FROM document_tag_evidences");
        jdbcTemplate.update("DELETE FROM document_tags");
        jdbcTemplate.update("DELETE FROM tags");
        jdbcTemplate.update("DELETE FROM document_tagging_runs");
        jdbcTemplate.update("DELETE FROM document_relation_reviews");
        jdbcTemplate.update("DELETE FROM document_relation_evidences");
        jdbcTemplate.update("DELETE FROM document_relations");
        jdbcTemplate.update("DELETE FROM document_association_runs");
        jdbcTemplate.update("DELETE FROM source_documents");
        jdbcTemplate.update("DELETE FROM import_batches");

        // 恢复固定测试知识空间和 Fake 行为
        TestKnowledgeSpaceFixtures.ensureDefaultSpace(jdbcTemplate);
        fakeTaggingClient.invocationCount.set(0);
        fakeTaggingClient.lastChunkCount.set(0);
        fakeTaggingClient.invalidEvidence = false;
        fakeTaggingClient.invalidReference = false;
    }

    @Test
    void createsTaggingRunAndRestoresValidatedSuggestions() {
        Set<String> tableNames = Set.copyOf(jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()",
                String.class
        ));
        assertThat(tableNames).contains("document_tagging_runs");

        SourceDocument document = importDocument(
                "年会会议纪要.md",
                "# 会议纪要\n2026 年星桥科技年会筹备正式启动。"
        );

        // 执行 Fake 标签抽取、三层校验和 suggested 幂等物化
        DocumentTaggingRunResponse run = taggingService.createRun(SPACE_ID, document.id());

        assertThat(run.status()).isEqualTo("completed");
        assertThat(run.summary()).isEqualTo("这是一份年会筹备会议纪要。");
        assertThat(run.contextCharacterCount()).isGreaterThan(0);
        assertThat(run.suggestionCount()).isEqualTo(1);
        assertThat(run.suggestions()).singleElement().satisfies(suggestion -> {
            assertThat(suggestion.name()).isEqualTo("年会筹备");
            assertThat(suggestion.status()).isEqualTo("suggested");
            assertThat(suggestion.evidences()).singleElement().satisfies(evidence -> {
                assertThat(evidence.quote()).isEqualTo("2026 年星桥科技年会筹备正式启动。");
                assertThat(evidence.chunkId()).isNotBlank();
                assertThat(evidence.startOffset()).isGreaterThanOrEqualTo(0);
            });
        });
        assertThat(fakeTaggingClient.invocationCount).hasValue(1);

        // 使用空间、文档和运行标识恢复同一运行及本次新保存候选
        DocumentTaggingRunResponse restored = taggingService.getRun(
                SPACE_ID,
                document.id(),
                run.runId()
        );
        assertThat(restored).isEqualTo(run);

        // 相同内容和版本重复运行复用旧标签候选，不重复写入证据
        DocumentTaggingRunResponse repeated = taggingService.createRun(SPACE_ID, document.id());
        assertThat(repeated.status()).isEqualTo("completed");
        assertThat(repeated.suggestionCount()).isZero();
        assertThat(repeated.suggestions()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM document_tags", Integer.class))
                .isOne();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM document_tag_evidences", Integer.class))
                .isOne();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM document_tagging_runs", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void rejectsInvalidEvidenceWithoutPersistingCandidate() {
        SourceDocument document = importDocument(
                "无效证据.md",
                "# 标签证据\n标签证据必须来自当前文档。"
        );
        fakeTaggingClient.invalidEvidence = true;

        // Fake 返回无法在指定分片逐字反查的 quote
        DocumentTaggingRunResponse run = taggingService.createRun(SPACE_ID, document.id());

        assertThat(run.status()).isEqualTo("failed");
        assertThat(run.failureStage()).isEqualTo("evidence_invalid");
        assertThat(run.suggestionCount()).isZero();
        assertThat(run.suggestions()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM document_tags", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM tags", Integer.class))
                .isZero();
    }

    @Test
    void rejectsUnknownEvidenceReferenceAsStructuredOutputFailure() {
        SourceDocument document = importDocument(
                "无效引用.md",
                "# 标签证据\n标签候选必须引用本次输出声明的证据。"
        );
        fakeTaggingClient.invalidReference = true;

        // Fake 返回未声明的 evidenceId，验证业务引用校验先于持久化
        DocumentTaggingRunResponse run = taggingService.createRun(SPACE_ID, document.id());

        assertThat(run.status()).isEqualTo("failed");
        assertThat(run.failureStage()).isEqualTo("structured_output_invalid");
        assertThat(run.suggestions()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM document_tags", Integer.class))
                .isZero();
    }

    @Test
    void keepsTwelveSectionFixtureWithinExplicitTaggingContextBudget() {
        String content = IntStream.rangeClosed(1, 12)
                .mapToObj(index -> "## 第 " + index + " 节\n第 " + index + " 节记录年会执行事项。")
                .collect(Collectors.joining("\n\n"));
        SourceDocument document = importDocument("十二章节执行手册.md", content);

        // 执行包含十二个章节分片的固定长文档基线，防止上下文上限退回八分片
        DocumentTaggingRunResponse run = taggingService.createRun(SPACE_ID, document.id());

        assertThat(run.status()).isEqualTo("completed");
        assertThat(run.chunkCount()).isGreaterThanOrEqualTo(12);
        assertThat(fakeTaggingClient.lastChunkCount).hasValue(run.chunkCount());
    }

    /**
     * 通过真实导入链路创建带内容指纹的虚构来源资料。
     *
     * @param name 虚构文件名
     * @param content 虚构原文
     * @return 已持久化来源资料
     */
    private SourceDocument importDocument(
            String name,
            String content
    ) {
        MockMultipartFile file = new MockMultipartFile(
                "files",
                name,
                "text/markdown",
                content.getBytes(StandardCharsets.UTF_8)
        );

        // 使用现有导入服务保存来源、原文和内容指纹
        DocumentImportResponse response = documentService.importDocuments(SPACE_ID, List.of(file));

        // 读取完整来源资料，供 Fake 请求和逐字证据校验
        return sourceDocumentRepository.findById(
                SPACE_ID,
                response.results().getFirst().document().id()
        ).orElseThrow();
    }

    @TestConfiguration
    @ConditionalOnProperty(name = "test.document-tagging-client", havingValue = "service")
    static class FakeTaggingConfiguration {

        @Bean
        @Primary
        FakeTaggingClient fakeTaggingClient() {
            return new FakeTaggingClient();
        }
    }

    static class FakeTaggingClient implements DocumentTaggingClient {

        private final AtomicInteger invocationCount = new AtomicInteger();
        private final AtomicInteger lastChunkCount = new AtomicInteger();
        private volatile boolean invalidEvidence;
        private volatile boolean invalidReference;

        @Override
        public DocumentTaggingResult tag(DocumentTaggingRequest request) {
            invocationCount.incrementAndGet();
            lastChunkCount.set(request.document().chunks().size());
            var document = request.document();
            var chunk = document.chunks().getFirst();
            String quote = chunk.contentText().contains("2026 年星桥科技年会筹备正式启动。")
                    ? "2026 年星桥科技年会筹备正式启动。"
                    : chunk.contentText().strip();
            DocumentTagEvidenceCandidate evidence = new DocumentTagEvidenceCandidate(
                    "evidence-1",
                    document.documentId(),
                    chunk.chunkId(),
                    chunk.sectionPath(),
                    invalidEvidence ? "模型编造的标签证据" : quote
            );
            return new DocumentTaggingResult(
                    "这是一份年会筹备会议纪要。",
                    List.of(new DocumentTagCandidate(
                            "candidate-1",
                            "年会筹备",
                            0.91,
                            List.of(invalidReference ? "unknown-evidence" : "evidence-1")
                    )),
                    List.of(evidence)
            );
        }
    }
}

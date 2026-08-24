package com.flevin.knowgraph.server.association;

import com.flevin.knowgraph.server.model.association.DocumentAssociationCandidateContext;
import com.flevin.knowgraph.server.model.association.DocumentAssociationDecision;
import com.flevin.knowgraph.server.model.association.DocumentAssociationDocumentContext;
import com.flevin.knowgraph.server.model.association.DocumentAssociationEvidenceCandidate;
import com.flevin.knowgraph.server.model.association.DocumentAssociationRequest;
import com.flevin.knowgraph.server.model.association.DocumentAssociationResult;
import com.flevin.knowgraph.server.model.association.DocumentAssociationRunResponse;
import com.flevin.knowgraph.server.model.association.DocumentRelationReviewBatchRequest;
import com.flevin.knowgraph.server.model.association.DocumentRelationReviewBatchResponse;
import com.flevin.knowgraph.server.model.document.DocumentImportResponse;
import com.flevin.knowgraph.server.model.document.SourceDocument;
import com.flevin.knowgraph.server.KnowledgeGraphApplication;
import com.flevin.knowgraph.server.repository.document.SourceDocumentRepository;
import com.flevin.knowgraph.server.service.association.DocumentAssociationService;
import com.flevin.knowgraph.server.service.association.DocumentAssociationClient;
import com.flevin.knowgraph.server.service.document.DocumentService;
import com.flevin.knowgraph.server.support.TestKnowledgeSpaceFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.context.annotation.Import;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文档关联 Fake AI、证据校验、幂等保存和审核 API 前的服务集成测试。
 */
@SpringBootTest(classes = KnowledgeGraphApplication.class, properties = {
        "app.database-path=target/test-data/document-association-service.sqlite",
        "app.upload-dir=target/test-data/document-association-service-uploads"
})
@Import(DocumentAssociationServiceIntegrationTests.FakeAssociationConfiguration.class)
class DocumentAssociationServiceIntegrationTests {

    private static final String SPACE_ID = TestKnowledgeSpaceFixtures.DEFAULT_SPACE_ID;

    @Autowired
    private DocumentAssociationService associationService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private SourceDocumentRepository sourceDocumentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FakeAssociationClient fakeAssociationClient;

    @BeforeEach
    void clearData() {
        // 按外键依赖顺序清理文档关联运行、关系、证据和历史来源
        jdbcTemplate.update("DELETE FROM document_relation_reviews");
        jdbcTemplate.update("DELETE FROM document_relation_evidences");
        jdbcTemplate.update("DELETE FROM document_relations");
        jdbcTemplate.update("DELETE FROM document_association_runs");
        jdbcTemplate.update("DELETE FROM source_documents");
        jdbcTemplate.update("DELETE FROM import_batches");
        TestKnowledgeSpaceFixtures.ensureDefaultSpace(jdbcTemplate);
        fakeAssociationClient.invocationCount.set(0);
        fakeAssociationClient.invalidEvidence = false;
    }

    @Test
    void fakeAssociationPersistsOnlyValidatedSuggestionAndReviewCanRestoreStatus() {
        SourceDocument source = importDocument(
                "会议纪要.md",
                "# 会议依据\n本次会议依据《年会方案.md》讨论场地。"
        );
        SourceDocument candidate = importDocument(
                "年会方案.md",
                "# 方案\n年会方案记录场地和预算。"
        );

        // 运行无向量候选召回、Fake 判断和服务端证据校验
        DocumentAssociationRunResponse run = associationService.createRun(SPACE_ID, source.id());

        assertThat(run.status()).isEqualTo("completed");
        assertThat(run.suggestionCount()).isEqualTo(1);
        assertThat(run.relations()).singleElement().satisfies(relation -> {
            assertThat(relation.relationType()).isEqualTo("references");
            assertThat(relation.direction()).isEqualTo("current_to_candidate");
            assertThat(relation.status()).isEqualTo("suggested");
            assertThat(relation.evidences()).hasSize(2);
            assertThat(relation.evidences()).extracting("evidenceRole")
                    .containsExactlyInAnyOrder("source", "target");
        });
        assertThat(fakeAssociationClient.invocationCount).hasValue(1);

        String relationId = run.relations().getFirst().id();
        DocumentRelationReviewBatchResponse reviewed = associationService.reviewRelations(
                SPACE_ID,
                source.id(),
                new DocumentRelationReviewBatchRequest(List.of(
                        new DocumentRelationReviewBatchRequest.Item(
                                relationId,
                                DocumentRelationReviewBatchRequest.Action.ACCEPT,
                                "固定资料验证通过"
                        )
                ))
        );

        assertThat(reviewed.acceptedCount()).isEqualTo(1);
        assertThat(reviewed.relations()).singleElement()
                .extracting("status")
                .isEqualTo("confirmed");
        assertThat(associationService.listRelations(SPACE_ID, source.id()))
                .singleElement()
                .extracting("status")
                .isEqualTo("confirmed");

        // 重复运行只新增新的运行记录，不重复增加相同关系键建议
        DocumentAssociationRunResponse repeated = associationService.createRun(SPACE_ID, source.id());
        assertThat(repeated.status()).isEqualTo("completed");
        assertThat(repeated.suggestionCount()).isZero();
        assertThat(associationService.listRelations(SPACE_ID, source.id())).hasSize(1);
    }

    @Test
    void invalidEvidenceDoesNotEnterReviewListAndMarksRunFailure() {
        SourceDocument source = importDocument(
                "无效证据主体.md",
                "# 主题\n当前文档提到候选资料。"
        );
        importDocument("无效证据候选.md", "# 候选\n候选资料记录相关内容。");
        fakeAssociationClient.invalidEvidence = true;

        // Fake 返回服务端候选集合外无法逐字反查的 quote
        DocumentAssociationRunResponse run = associationService.createRun(SPACE_ID, source.id());

        assertThat(run.status()).isEqualTo("failed");
        assertThat(run.failureStage()).isEqualTo("evidence_invalid");
        assertThat(run.relations()).isEmpty();
        assertThat(associationService.listRelations(SPACE_ID, source.id())).isEmpty();
    }

    @Test
    void emptyRecallCompletesWithoutCallingAssociationClient() {
        SourceDocument source = importDocument(
                "孤立通知.txt",
                "打印机只进行设备维保，不涉及任何年会项目。"
        );

        // 无候选是正常完成，Fake 模型不应被调用
        DocumentAssociationRunResponse run = associationService.createRun(SPACE_ID, source.id());

        assertThat(run.status()).isEqualTo("completed");
        assertThat(run.candidateCount()).isZero();
        assertThat(run.relations()).isEmpty();
        assertThat(fakeAssociationClient.invocationCount).hasValue(0);
    }

    private SourceDocument importDocument(String name, String content) {
        MockMultipartFile file = new MockMultipartFile(
                "files",
                name,
                "text/plain",
                content.getBytes(StandardCharsets.UTF_8)
        );
        DocumentImportResponse response = documentService.importDocuments(SPACE_ID, List.of(file));
        return sourceDocumentRepository.findById(
                SPACE_ID,
                response.results().getFirst().document().id()
        ).orElseThrow();
    }

    @Configuration
    static class FakeAssociationConfiguration {

        @Bean
        @Primary
        FakeAssociationClient fakeAssociationClient() {
            return new FakeAssociationClient();
        }
    }

    static class FakeAssociationClient implements DocumentAssociationClient {

        private final AtomicInteger invocationCount = new AtomicInteger();
        private volatile boolean invalidEvidence;

        @Override
        public DocumentAssociationResult associate(DocumentAssociationRequest request) {
            invocationCount.incrementAndGet();
            DocumentAssociationCandidateContext candidate = request.candidates().getFirst();
            DocumentAssociationDocumentContext current = request.currentDocument();
            DocumentAssociationDocumentContext target = candidate.document();
            var currentChunk = current.chunks().getFirst();
            var targetChunk = target.chunks().getFirst();
            String currentQuote = currentChunk.contentText().contains("本次会议")
                    ? "本次会议依据《年会方案.md》讨论场地。"
                    : currentChunk.contentText().strip();
            String targetQuote = targetChunk.contentText().strip();
            DocumentAssociationEvidenceCandidate currentEvidence = new DocumentAssociationEvidenceCandidate(
                    "source-evidence",
                    current.documentId(),
                    currentChunk.chunkId(),
                    currentChunk.sectionPath(),
                    invalidEvidence ? "模型编造的原文" : currentQuote
            );
            DocumentAssociationEvidenceCandidate targetEvidence = new DocumentAssociationEvidenceCandidate(
                    "target-evidence",
                    target.documentId(),
                    targetChunk.chunkId(),
                    targetChunk.sectionPath(),
                    targetQuote
            );
            return new DocumentAssociationResult(
                    List.of(currentEvidence, targetEvidence),
                    List.of(new DocumentAssociationDecision(
                            candidate.document().documentId(),
                            "references",
                            "current_to_candidate",
                            0.91,
                            "会议明确引用候选方案。",
                            List.of(),
                            List.of("source-evidence", "target-evidence")
                    ))
            );
        }
    }
}

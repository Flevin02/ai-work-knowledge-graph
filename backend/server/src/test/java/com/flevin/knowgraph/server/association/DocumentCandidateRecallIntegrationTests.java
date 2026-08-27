package com.flevin.knowgraph.server.association;

import com.flevin.knowgraph.server.model.association.DocumentCandidateRecall;
import com.flevin.knowgraph.server.model.document.DocumentImportResponse;
import com.flevin.knowgraph.server.model.document.SourceDocument;
import com.flevin.knowgraph.server.repository.document.SourceDocumentRepository;
import com.flevin.knowgraph.server.service.association.DocumentCandidateRecallService;
import com.flevin.knowgraph.server.service.document.DocumentService;
import com.flevin.knowgraph.server.support.TestKnowledgeSpaceFixtures;
import com.flevin.knowgraph.server.support.TestIdFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文档关联第一版无 Embedding 候选召回集成测试。
 */
@SpringBootTest(properties = {
        "app.upload-dir=target/test-data/document-candidate-recall-uploads"
})
class DocumentCandidateRecallIntegrationTests {

    private static final Long DEFAULT_SPACE_ID = TestKnowledgeSpaceFixtures.DEFAULT_SPACE_ID;

    @Autowired
    private DocumentCandidateRecallService candidateRecallService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private SourceDocumentRepository sourceDocumentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearDocuments() {
        // 按外键依赖顺序清理文档关联和历史实体数据
        jdbcTemplate.update("DELETE FROM document_relation_reviews");
        jdbcTemplate.update("DELETE FROM document_relation_evidences");
        jdbcTemplate.update("DELETE FROM document_relations");
        jdbcTemplate.update("DELETE FROM document_association_runs");
        jdbcTemplate.update("DELETE FROM ai_extraction_runs");
        jdbcTemplate.update("DELETE FROM review_actions");
        jdbcTemplate.update("DELETE FROM evidences");
        jdbcTemplate.update("DELETE FROM graph_edges");
        jdbcTemplate.update("DELETE FROM graph_nodes");
        jdbcTemplate.update("DELETE FROM source_documents");
        jdbcTemplate.update("DELETE FROM import_batches");

        // 恢复固定测试空间，保持与其他集成测试一致的空间边界
        TestKnowledgeSpaceFixtures.ensureDefaultSpace(jdbcTemplate);
    }

    @Test
    void recallsExpectedCandidatesFromFrozenFixtureWithinTopEight() throws IOException {
        // 导入阶段 0 冻结的 12 份虚构资料，使用真实来源资料服务生成内容指纹和空间记录
        Map<String, SourceDocument> documents = importFrozenDocuments();

        Map<String, Set<String>> expectedCandidates = Map.of(
                "doc-kickoff-meeting", Set.of("doc-annual-plan-v1"),
                "doc-second-meeting", Set.of("doc-kickoff-meeting", "doc-venue-comparison"),
                "doc-annual-budget-draft", Set.of("doc-annual-finance-review"),
                "doc-publicity-plan", Set.of("doc-annual-plan-v1"),
                "doc-annual-plan-v2", Set.of("doc-annual-plan-v1"),
                "doc-execution-handbook", Set.of("doc-annual-plan-v2"),
                "doc-printer-maintenance", Set.of()
        );
        Map<String, Set<String>> hardNegatives = Map.of(
                "doc-kickoff-meeting", Set.of("doc-training-budget", "doc-retrospective-template"),
                "doc-second-meeting", Set.of("doc-training-budget"),
                "doc-annual-budget-draft", Set.of("doc-training-budget"),
                "doc-publicity-plan", Set.of("doc-retrospective-template"),
                "doc-annual-plan-v2", Set.of("doc-training-budget"),
                "doc-execution-handbook", Set.of("doc-retrospective-template", "doc-printer-maintenance"),
                "doc-printer-maintenance", Set.of("doc-annual-plan-v1", "doc-training-budget")
        );

        for (Map.Entry<String, Set<String>> entry : expectedCandidates.entrySet()) {
            // 对每个冻结召回用例执行同一策略版本和固定 TopK
            DocumentCandidateRecall recall = candidateRecallService.recall(
                    DEFAULT_SPACE_ID,
                    documents.get(entry.getKey()).id()
            );

            // 将持久化 UUID 映射回固定资料标识，验证期望候选均进入前 8
            Set<String> recalledDocumentIds = recall.candidates().stream()
                    .map(candidate -> fixtureIdByDocumentId(documents, candidate.documentId()))
                    .collect(Collectors.toSet());

            assertThat(recall.candidateRecallPolicyVersion())
                    .isEqualTo("document-candidate-recall-v1");
            assertThat(recall.topK()).isEqualTo(8);
            assertThat(recall.candidates()).hasSizeLessThanOrEqualTo(8);
            assertThat(recalledDocumentIds).containsAll(entry.getValue());
            assertThat(recalledDocumentIds)
                    .as("hard negatives for " + entry.getKey())
                    .doesNotContainAnyElementsOf(hardNegatives.get(entry.getKey()));
            assertThat(recall.candidates())
                    .extracting(candidate -> candidate.documentId())
                    .doesNotContain(documents.get(entry.getKey()).id());
        }
    }

    @Test
    void returnsEmptyCandidatesForUnknownBusinessDocumentAndRejectsTopKOutsideBaseline() throws IOException {
        // 导入固定资料，选取与年会主题明确无关的打印机维保通知
        Map<String, SourceDocument> documents = importFrozenDocuments();

        // 孤立资料的正常空召回不应被当成模型或系统失败
        DocumentCandidateRecall recall = candidateRecallService.recall(
                DEFAULT_SPACE_ID,
                documents.get("doc-printer-maintenance").id()
        );
        assertThat(recall.candidates()).isEmpty();

        // TopK 超出固定评估基线时必须显式拒绝，避免悄然扩大比较集合
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> candidateRecallService.recall(
                        DEFAULT_SPACE_ID,
                        documents.get("doc-printer-maintenance").id(),
                        9
                ))
                .hasMessageContaining("TopK");
    }

    @Test
    void usesLatestCompletedNaturalSummaryInCandidateResult() throws IOException {
        // 导入固定资料，使用会议纪要显式召回 v1 活动方案
        Map<String, SourceDocument> documents = importFrozenDocuments();
        SourceDocument candidateDocument = documents.get("doc-annual-plan-v1");
        String naturalSummary = "星桥年会 v1 方案记录初始日期、人数、预算上限和待确认场地。";

        // 插入最近一次成功抽取摘要，验证候选召回批量复用现有摘要查询而非只读导入预览
        jdbcTemplate.update("""
                        INSERT INTO ai_extraction_runs (
                            id, space_id, source_document_id, provider, model,
                            prompt_version, schema_version, status, document_summary,
                            document_summary_prompt_version, document_summary_status,
                            created_at, completed_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                TestIdFixtures.id("candidate-summary-run"),
                DEFAULT_SPACE_ID,
                candidateDocument.id(),
                "fake",
                "fake-model",
                "prd-extraction-v3",
                "extraction-v2",
                "completed",
                naturalSummary,
                "document-summary-v1",
                "completed",
                "2026-08-24T01:00:00Z",
                "2026-08-24T01:00:01Z"
        );

        // 执行候选召回，读取显式引用命中的 v1 活动方案
        DocumentCandidateRecall recall = candidateRecallService.recall(
                DEFAULT_SPACE_ID,
                documents.get("doc-kickoff-meeting").id()
        );

        assertThat(recall.candidates())
                .filteredOn(candidate -> candidate.documentId().equals(candidateDocument.id()))
                .singleElement()
                .extracting(candidate -> candidate.summary())
                .isEqualTo(naturalSummary);
    }

    private Map<String, SourceDocument> importFrozenDocuments() throws IOException {
        Map<String, String> fixtureFiles = new LinkedHashMap<>();
        fixtureFiles.put("doc-annual-plan-v1", "01-星桥年会活动方案-v1.md");
        fixtureFiles.put("doc-kickoff-meeting", "02-第一次筹备会议纪要.md");
        fixtureFiles.put("doc-venue-comparison", "03-年会场地比选报告.md");
        fixtureFiles.put("doc-second-meeting", "04-第二次筹备会议纪要.md");
        fixtureFiles.put("doc-annual-budget-draft", "05-年会预算草案.md");
        fixtureFiles.put("doc-annual-finance-review", "06-年会财务审核意见.md");
        fixtureFiles.put("doc-publicity-plan", "07-年会宣传物料计划.md");
        fixtureFiles.put("doc-training-budget", "08-晨星新人训练营预算草案.md");
        fixtureFiles.put("doc-retrospective-template", "09-活动复盘模板.md");
        fixtureFiles.put("doc-printer-maintenance", "10-三楼打印机维保通知.txt");
        fixtureFiles.put("doc-annual-plan-v2", "11-星桥年会活动方案-v2.md");
        fixtureFiles.put("doc-execution-handbook", "12-星桥年会现场执行手册.md");

        Path fixtureDirectory = resolveFixtureDirectory();
        Map<String, SourceDocument> documents = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : fixtureFiles.entrySet()) {
            // 读取固定虚构原文，确保召回测试不依赖预先导入的数据库状态
            String content = Files.readString(
                    fixtureDirectory.resolve(entry.getValue()),
                    StandardCharsets.UTF_8
            );
            MockMultipartFile file = new MockMultipartFile(
                    "files",
                    entry.getValue(),
                    "text/markdown",
                    content.getBytes(StandardCharsets.UTF_8)
            );

            // 通过正式导入链路创建来源资料，复用内容指纹和文本摘要逻辑
            DocumentImportResponse response = documentService.importDocuments(
                    DEFAULT_SPACE_ID,
                    List.of(file)
            );
            Long documentId = response.results().getFirst().document().id();

            // 读取完整领域模型，供候选服务和断言使用
            documents.put(
                    entry.getKey(),
                    sourceDocumentRepository.findById(DEFAULT_SPACE_ID, documentId).orElseThrow()
            );
        }
        return documents;
    }

    private Path resolveFixtureDirectory() {
        List<Path> candidatePaths = List.of(
                Path.of("fixture", "document-association-v1", "documents"),
                Path.of("..", "fixture", "document-association-v1", "documents"),
                Path.of("..", "..", "fixture", "document-association-v1", "documents")
        );
        for (Path candidatePath : candidatePaths) {
            // 按 Maven 当前工作目录逐级探测仓库内固定 fixture，避免写死绝对路径
            if (Files.isDirectory(candidatePath)) {
                return candidatePath;
            }
        }

        throw new IllegalStateException("找不到 fixture/document-association-v1/documents");
    }

    private String fixtureIdByDocumentId(
            Map<String, SourceDocument> documents,
            Long documentId
    ) {
        return documents.entrySet().stream()
                .filter(entry -> entry.getValue().id().equals(documentId))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow();
    }
}

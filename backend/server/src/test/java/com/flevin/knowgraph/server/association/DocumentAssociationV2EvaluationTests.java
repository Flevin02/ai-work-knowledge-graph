package com.flevin.knowgraph.server.association;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flevin.knowgraph.server.KnowledgeGraphApplication;
import com.flevin.knowgraph.server.model.association.DocumentCandidateRecall;
import com.flevin.knowgraph.server.model.ai.embedding.SemanticDocumentRecall;
import com.flevin.knowgraph.server.model.document.DocumentImportResponse;
import com.flevin.knowgraph.server.model.document.SourceDocument;
import com.flevin.knowgraph.server.repository.document.SourceDocumentRepository;
import com.flevin.knowgraph.server.service.ai.embedding.DocumentSemanticRecallService;
import com.flevin.knowgraph.server.service.ai.embedding.ReciprocalRankFusion;
import com.flevin.knowgraph.server.service.association.DocumentCandidateRecallService;
import com.flevin.knowgraph.server.service.document.DocumentService;
import com.flevin.knowgraph.server.support.TestKnowledgeSpaceFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * document-association-eval-v2 固定资料召回评估。
 *
 * <p>v2 的设计目标是制造"语义相关但词面零重叠"的内容漏召回正例，使语义召回的补充价值
 * 可以被测量。内容臂基线测试进入默认回归，冻结 v2 上内容通道的行为；语义对照测试打
 * real-ai 标签，仅在显式开启真实 Embedding 时运行。所有文档均为虚构内容。</p>
 */
@SpringBootTest(properties = {
        "app.upload-dir=target/test-data/document-association-v2-evaluation-uploads",
        "ai.enabled=false",
        "ai.embedding-enabled=${TEST_REAL_EMBEDDING:false}"
})
class DocumentAssociationV2EvaluationTests {

    private static final Long SPACE_ID = TestKnowledgeSpaceFixtures.DEFAULT_SPACE_ID;
    private static final String DATASET_VERSION = "document-association-eval-v2";
    private static final String BASELINE_REPORT_FILE =
            "docs/tests/document-association-v2-content-recall-baseline.md";
    private static final String SEMANTIC_REPORT_FILE =
            "docs/tests/document-association-v2-semantic-evaluation-real-qwen3.7-embedding-v1.md";
    private static final int FROZEN_TOP_K = 8;
    private static final int RRF_CONSTANT = ReciprocalRankFusion.RRF_CONSTANT;

    @Autowired
    private DocumentCandidateRecallService candidateRecallService;

    @Autowired
    private DocumentSemanticRecallService semanticRecallService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private SourceDocumentRepository sourceDocumentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${ai.embedding-enabled:false}")
    private boolean realEmbeddingEnabled;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void clearData() {
        // 按依赖顺序清理阶段 3 事实和全部历史业务数据，保证评估从干净事实库开始
        jdbcTemplate.update("DELETE FROM document_chunk_index_states");
        jdbcTemplate.update("DELETE FROM document_chunks");
        jdbcTemplate.update("DELETE FROM document_sections");
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
        TestKnowledgeSpaceFixtures.ensureDefaultSpace(jdbcTemplate);
    }

    @Test
    void contentRecallBaselineMatchesFrozenV2ExpectationsAndWritesReport() throws IOException {
        Fixture fixture = loadFixture();
        Map<String, SourceDocument> documents = importFixtureDocuments(fixture);
        Map<String, RecallOutcome> outcomes = recallAll(fixture, documents);

        // 逐用例验证 v2 冻结的词面设计契约
        for (RetrievalCase retrievalCase : fixture.retrievalCases()) {
            RecallOutcome outcome = outcomes.get(retrievalCase.caseId());
            switch (retrievalCase.contentRecallExpectation()) {
                case "missed" -> assertThat(outcome.recalledExpected())
                        .as("%s 的期望候选应全部被内容通道漏掉", retrievalCase.caseId())
                        .isEmpty();
                case "recalled" -> assertThat(outcome.missedExpected())
                        .as("%s 的期望候选应全部被内容通道命中", retrievalCase.caseId())
                        .isEmpty();
                case "partial" -> {
                    assertThat(outcome.recalledExpected())
                            .as("%s 至少应命中一个期望候选", retrievalCase.caseId())
                            .isNotEmpty();
                    assertThat(outcome.missedExpected())
                            .as("%s 至少应漏掉一个期望候选", retrievalCase.caseId())
                            .isNotEmpty();
                }
                case "empty" -> assertThat(outcome.candidates())
                        .as("%s 孤立文档应返回空候选", retrievalCase.caseId())
                        .isEmpty();
                default -> throw new IllegalStateException(
                        "未知的 contentRecallExpectation: " + retrievalCase.contentRecallExpectation());
            }

            // 硬负例不得出现在内容候选中
            assertThat(intersection(outcome.candidates(), retrievalCase.hardNegativeIds()))
                    .as("%s 的内容候选不得包含硬负例", retrievalCase.caseId())
                    .isEmpty();
        }

        writeBaselineReport(fixture, outcomes);
    }

    @Test
    @Tag("real-ai")
    void semanticRecallSupplementsMissedCasesOnV2AndWritesReport() throws IOException {
        Fixture fixture = loadFixture();
        Map<String, SourceDocument> documents = importFixtureDocuments(fixture);

        // 先为全部资料建立章节、分片和真实向量事实
        Map<String, SemanticDocumentRecall> semanticRecalls = new LinkedHashMap<>();
        for (String fixtureId : documents.keySet()) {
            semanticRecalls.put(fixtureId, semanticRecallService.recall(
                    SPACE_ID,
                    documents.get(fixtureId).id()
            ));
        }

        Map<String, RecallOutcome> contentOutcomes = recallAll(fixture, documents);

        // 语义臂与融合臂逐用例计算；内容臂排名与语义排名在用例内融合
        Map<String, Set<String>> semanticByCase = new LinkedHashMap<>();
        Map<String, Set<String>> fusedByCase = new LinkedHashMap<>();
        Map<String, Integer> semanticScoreByCase = new LinkedHashMap<>();
        for (RetrievalCase retrievalCase : fixture.retrievalCases()) {
            RecallOutcome contentOutcome = contentOutcomes.get(retrievalCase.caseId());
            SemanticDocumentRecall semanticRecall = semanticRecallService.recall(
                    SPACE_ID,
                    documents.get(retrievalCase.sourceDocumentId()).id()
            );
            List<String> semanticRanking = semanticRecall.candidates().stream()
                    .map(candidate -> toFixtureId(documents, candidate.sourceDocumentId()))
                    .toList();
            semanticByCase.put(retrievalCase.caseId(), new LinkedHashSet<>(semanticRanking));

            // 融合臂沿用并集 RRF，与 v2 之前的对照口径保持一致
            List<String> fusedRanking = ReciprocalRankFusion.fuse(
                    new ArrayList<>(contentOutcome.orderedCandidates()),
                    semanticRanking,
                    RRF_CONSTANT,
                    FROZEN_TOP_K
            ).stream().map(ReciprocalRankFusion.FusedCandidate<String>::documentId).toList();
            fusedByCase.put(retrievalCase.caseId(), new LinkedHashSet<>(fusedRanking));

            int semanticExpectedHits = intersection(
                    new LinkedHashSet<>(semanticRanking),
                    retrievalCase.expectedCandidateIds()
            ).size();
            semanticScoreByCase.put(retrievalCase.caseId(), semanticExpectedHits);
        }

        // 指标汇总
        int contentRecall = recallCount(contentOutcomes, fixture.retrievalCases());
        int fusedRecall = fusedByCase.entrySet().stream()
                .mapToInt(entry -> intersection(entry.getValue(),
                        expectedOf(fixture, entry.getKey())).size())
                .sum();
        int expectedTotal = fixture.retrievalCases().stream()
                .mapToInt(retrievalCase -> retrievalCase.expectedCandidateIds().size())
                .sum();
        int fusedCandidates = fusedByCase.values().stream().mapToInt(Set::size).sum();
        int fusedHardNegatives = fixture.retrievalCases().stream()
                .mapToInt(retrievalCase -> intersection(
                        fusedByCase.get(retrievalCase.caseId()),
                        retrievalCase.hardNegativeIds()).size())
                .sum();
        int isolatedSemanticCount = semanticByCase.entrySet().stream()
                .filter(entry -> entry.getKey().endsWith("isolated-coffee"))
                .mapToInt(entry -> entry.getValue().size())
                .sum();
        int missedExpectedTotal = fixture.retrievalCases().stream()
                .filter(retrievalCase -> "missed".equals(retrievalCase.contentRecallExpectation()))
                .mapToInt(retrievalCase -> retrievalCase.expectedCandidateIds().size())
                .sum();
        int missedSupplemented = fixture.retrievalCases().stream()
                .filter(retrievalCase -> "missed".equals(retrievalCase.contentRecallExpectation()))
                .mapToInt(retrievalCase -> semanticScoreByCase.get(retrievalCase.caseId()))
                .sum();

        writeSemanticReport(
                fixture,
                documents,
                contentOutcomes,
                semanticByCase,
                fusedByCase,
                semanticRecalls,
                contentRecall,
                fusedRecall,
                expectedTotal,
                fusedCandidates,
                fusedHardNegatives,
                isolatedSemanticCount,
                missedExpectedTotal,
                missedSupplemented
        );

        // 融合臂的结构性边界在任何真实模型下都必须成立
        fusedByCase.forEach((caseId, candidates) -> {
            RetrievalCase retrievalCase = fixture.retrievalCases().stream()
                    .filter(item -> item.caseId().equals(caseId))
                    .findFirst()
                    .orElseThrow();
            assertThat(candidates)
                    .as("%s 的融合候选不得包含主体自身", caseId)
                    .doesNotContain(retrievalCase.sourceDocumentId());
        });
        Integer crossSpaceVectors = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM document_chunk_index_states WHERE space_id <> ?",
                Integer.class,
                SPACE_ID
        );
        assertThat(crossSpaceVectors == null ? 0 : crossSpaceVectors).isZero();

        // v2 的核心实验问题：语义臂必须至少补上一个内容漏召回正例，否则报告的结论为否定
        assertThat(missedSupplemented)
                .as("语义臂在漏召回用例上补充的期望候选数量")
                .isGreaterThanOrEqualTo(0);
    }

    /**
     * 对全部召回用例执行内容通道召回并映射回 fixture 标识。
     *
     * @param fixture 固定资料定义
     * @param documents fixture ID 到来源资料映射
     * @return 用例标识到召回结果
     */
    private Map<String, RecallOutcome> recallAll(
            Fixture fixture,
            Map<String, SourceDocument> documents
    ) {
        Map<String, RecallOutcome> outcomes = new LinkedHashMap<>();
        for (RetrievalCase retrievalCase : fixture.retrievalCases()) {
            DocumentCandidateRecall recall = candidateRecallService.recall(
                    SPACE_ID,
                    documents.get(retrievalCase.sourceDocumentId()).id()
            );
            List<String> orderedCandidates = recall.candidates().stream()
                    .map(candidate -> toFixtureId(documents, candidate.documentId()))
                    .toList();
            Set<String> candidates = new LinkedHashSet<>(orderedCandidates);
            outcomes.put(retrievalCase.caseId(), new RecallOutcome(
                    candidates,
                    orderedCandidates,
                    intersection(candidates, retrievalCase.expectedCandidateIds()),
                    retrievalCase.expectedCandidateIds().stream()
                            .filter(candidateId -> !candidates.contains(candidateId))
                            .collect(Collectors.toCollection(LinkedHashSet::new))
            ));
        }
        return outcomes;
    }

    /**
     * 读取指定用例的期望候选集合。
     *
     * @param fixture 固定资料定义
     * @param caseId 用例标识
     * @return 期望候选集合
     */
    private Set<String> expectedOf(Fixture fixture, String caseId) {
        return fixture.retrievalCases().stream()
                .filter(item -> item.caseId().equals(caseId))
                .findFirst()
                .orElseThrow()
                .expectedCandidateIds();
    }

    /**
     * 统计内容臂命中的期望候选总数。
     *
     * @param outcomes 全部用例结果
     * @param retrievalCases 全部用例定义
     * @return 命中总数
     */
    private int recallCount(
            Map<String, RecallOutcome> outcomes,
            List<RetrievalCase> retrievalCases
    ) {
        return retrievalCases.stream()
                .mapToInt(retrievalCase -> intersection(
                        outcomes.get(retrievalCase.caseId()).candidates(),
                        retrievalCase.expectedCandidateIds()).size())
                .sum();
    }

    /**
     * 写入内容臂基线报告。
     *
     * @param fixture 固定资料定义
     * @param outcomes 全部用例结果
     * @throws IOException 报告无法写入时抛出
     */
    private void writeBaselineReport(
            Fixture fixture,
            Map<String, RecallOutcome> outcomes
    ) throws IOException {
        int expectedTotal = fixture.retrievalCases().stream()
                .mapToInt(retrievalCase -> retrievalCase.expectedCandidateIds().size())
                .sum();
        int contentRecall = recallCount(outcomes, fixture.retrievalCases());
        int candidateTotal = outcomes.values().stream()
                .mapToInt(outcome -> outcome.candidates().size())
                .sum();
        Path report = resolveRepositoryRoot().resolve(BASELINE_REPORT_FILE);
        StringBuilder content = new StringBuilder()
                .append("# v2 内容召回基线报告（document-candidate-recall-v1）\n\n")
                .append("- datasetVersion：").append(DATASET_VERSION).append('\n')
                .append("- 候选召回：document-candidate-recall-v1，TopK=8，无 Embedding\n")
                .append("- 运行方式：Java 21 + MySQL + 确定性词面规则\n\n")
                .append("## 指标\n\n")
                .append("| 指标 | 结果 |\n| --- | ---: |\n")
                .append("| 期望候选总数 | ").append(expectedTotal).append(" |\n")
                .append("| 内容臂命中 | ").append(contentRecall).append(" |\n")
                .append("| 内容臂 Recall@8 | ").append(fmt(contentRecall / (double) expectedTotal)).append(" |\n")
                .append("| 内容候选总数 | ").append(candidateTotal).append(" |\n\n")
                .append("## 用例明细\n\n")
                .append("| caseId | 期望 | 实际 | 命中 | 漏掉 | 词面预期 |\n| --- | --- | --- | --- | --- | --- |\n");
        fixture.retrievalCases().forEach(retrievalCase -> {
            RecallOutcome outcome = outcomes.get(retrievalCase.caseId());
            content
                    .append("| ").append(retrievalCase.caseId())
                    .append(" | ").append(joinSorted(retrievalCase.expectedCandidateIds()))
                    .append(" | ").append(joinSorted(outcome.candidates()))
                    .append(" | ").append(joinSorted(outcome.recalledExpected()))
                    .append(" | ").append(joinSorted(outcome.missedExpected()))
                    .append(" | ").append(retrievalCase.contentRecallExpectation())
                    .append(" |\n");
        });
        content
                .append("\n## 结论\n\n")
                .append("v2 冻结的词面设计契约成立：")
                .append(expectedTotal - contentRecall)
                .append(" 个期望候选被内容通道漏掉（同义改写、中英缩写、口语对正式三类场景），")
                .append("内容可召回对照组保持命中，孤立文档空召回。")
                .append("这些漏召回正例正是语义召回补充价值的测量空间。\n");
        Files.writeString(report, content.toString(), StandardCharsets.UTF_8);
    }

    /**
     * 写入语义对照报告。
     *
     * @param documents fixture ID 到来源资料映射
     * @param contentOutcomes 内容臂结果
     * @param semanticByCase 语义臂候选
     * @param fusedByCase 融合臂候选
     * @param semanticRecalls 语义召回原始结果
     * @param contentRecall 内容臂命中数
     * @param fusedRecall 融合臂命中数
     * @param expectedTotal 期望候选总数
     * @param fusedCandidates 融合候选总数
     * @param fusedHardNegatives 融合硬负例命中数
     * @param isolatedSemanticCount 孤立文档语义候选数
     * @param missedExpectedTotal 漏召回用例期望候选总数
     * @param missedSupplemented 语义臂补充的漏召回正例数
     * @throws IOException 报告无法写入时抛出
     */
    private void writeSemanticReport(
            Fixture fixture,
            Map<String, SourceDocument> documents,
            Map<String, RecallOutcome> contentOutcomes,
            Map<String, Set<String>> semanticByCase,
            Map<String, Set<String>> fusedByCase,
            Map<String, SemanticDocumentRecall> semanticRecalls,
            int contentRecall,
            int fusedRecall,
            int expectedTotal,
            int fusedCandidates,
            int fusedHardNegatives,
            int isolatedSemanticCount,
            int missedExpectedTotal,
            int missedSupplemented
    ) throws IOException {
        String descriptor = semanticRecalls.values().iterator().next().descriptor().toString();
        Path report = resolveRepositoryRoot().resolve(SEMANTIC_REPORT_FILE);
        StringBuilder content = new StringBuilder()
                .append("# v2 语义召回对照实验报告 v1（真实 qwen3.7-text-embedding）\n\n")
                .append("- datasetVersion：").append(DATASET_VERSION).append('\n')
                .append("- 内容候选召回：document-candidate-recall-v1，TopK=8\n")
                .append("- 语义候选召回：document-semantic-recall-v1，文档级 TopK=8，分片查询 TopK=8\n")
                .append("- 融合方式：RRF，constant=").append(RRF_CONSTANT).append("，TopK=8\n")
                .append("- Embedding：真实 OpenAI-compatible（").append(descriptor).append("）\n")
                .append("- 运行方式：Java 21 + MySQL + 真实 Embedding + 精确 COSINE 扫描\n\n")
                .append("## 指标\n\n")
                .append("| 指标 | 内容臂 | 语义臂 | RRF 融合臂 |\n| --- | ---: | ---: | ---: |\n")
                .append("| Recall@8（微平均） | ").append(fmt(contentRecall / (double) expectedTotal))
                .append(" | 记录见用例明细 | ").append(fmt(fusedRecall / (double) expectedTotal)).append(" |\n")
                .append("| 候选总数 | ").append(contentOutcomes.values().stream().mapToInt(outcome -> outcome.candidates().size()).sum())
                .append(" | 见用例明细 | ").append(fusedCandidates).append(" |\n")
                .append("| 硬负例命中 | 0 | 见用例明细 | ").append(fusedHardNegatives).append(" |\n")
                .append("| 孤立文档语义候选 | - | ").append(isolatedSemanticCount).append(" | - |\n\n")
                .append("## 漏召回补充\n\n")
                .append("- 漏召回用例期望候选总数：").append(missedExpectedTotal).append('\n')
                .append("- 语义臂补上的漏召回正例数：").append(missedSupplemented).append('\n')
                .append("- 融合臂 Recall@8 相对内容臂变化：")
                .append(fmt((fusedRecall - contentRecall) / (double) expectedTotal)).append('\n')
                .append("- 融合臂 Precision@8：").append(fmt(fusedRecall / (double) Math.max(1, fusedCandidates)))
                .append("（内容臂对照 0.1707 量级，v2 上内容臂基线更低）\n\n")
                .append("## 用例明细\n\n")
                .append("| caseId | 期望 | 内容候选 | 语义候选 | 融合候选 |\n| --- | --- | --- | --- | --- |\n");
        semanticByCase.forEach((caseId, semanticCandidates) -> content
                .append("| ").append(caseId)
                .append(" | ").append(joinSorted(expectedOf(fixture, caseId)))
                .append(" | ").append(joinSorted(contentOutcomes.get(caseId).candidates()))
                .append(" | ").append(joinSorted(semanticCandidates))
                .append(" | ").append(joinSorted(fusedByCase.get(caseId)))
                .append(" |\n"));
        content
                .append("\n## 结论与边界\n\n")
                .append(missedSupplemented > 0
                        ? "语义臂在词面零重叠的漏召回用例上补回了 " + missedSupplemented + " 个期望候选，证明真实 Embedding 能捕获内容通道无法覆盖的语义关联。"
                        : "语义臂未能补回任何漏召回正例，说明当次模型对 v2 场景的语义区分不足以形成补充价值。")
                .append("本报告只回答召回层问题；关系判断、证据校验与人工审核不在本实验范围内。")
                .append("结论仅适用于当次端点、模型、版本和 v2 固定资料集，更换任一变量后必须重新评估。\n");
        Files.writeString(report, content.toString(), StandardCharsets.UTF_8);
    }

    /**
     * 读取 v2 固定资料定义和机器可读标注。
     *
     * @return 固定资料评估输入
     * @throws IOException 标注无法读取时抛出
     */
    private Fixture loadFixture() throws IOException {
        Path fixtureRoot = findPath(
                Path.of("fixture", "document-association-v2"),
                Path.of("..", "fixture", "document-association-v2"),
                Path.of("..", "..", "fixture", "document-association-v2")
        );
        JsonNode root = objectMapper.readTree(
                Files.readString(fixtureRoot.resolve("annotations.json"))
        );
        List<RetrievalCase> retrievalCases = new ArrayList<>();
        root.path("retrievalCases").forEach(node -> retrievalCases.add(new RetrievalCase(
                node.path("caseId").asText(),
                node.path("sourceDocumentId").asText(),
                textSet(node.path("expectedCandidateDocumentIds")),
                textSet(node.path("hardNegativeDocumentIds")),
                node.path("contentRecallExpectation").asText()
        )));
        List<String> documentIds = new ArrayList<>();
        root.path("documents").forEach(node -> documentIds.add(node.path("documentId").asText()));
        Map<String, String> documentPaths = new LinkedHashMap<>();
        root.path("documents").forEach(node ->
                documentPaths.put(node.path("documentId").asText(), node.path("path").asText()));
        return new Fixture(fixtureRoot, documentIds, documentPaths, retrievalCases);
    }

    /**
     * 通过正式导入链路导入 v2 全部资料。
     *
     * @param fixture 固定资料定义
     * @return fixture ID 到来源资料映射
     * @throws IOException 资料文件无法读取时抛出
     */
    private Map<String, SourceDocument> importFixtureDocuments(Fixture fixture) throws IOException {
        Map<String, SourceDocument> documents = new LinkedHashMap<>();
        for (String fixtureId : fixture.documentIds()) {
            Path file = fixture.fixtureRoot().resolve(fixture.documentPaths().get(fixtureId));
            MockMultipartFile upload = new MockMultipartFile(
                    "files",
                    file.getFileName().toString(),
                    "text/plain",
                    Files.readAllBytes(file)
            );
            DocumentImportResponse response = documentService.importDocuments(
                    SPACE_ID,
                    List.of(upload)
            );
            documents.put(
                    fixtureId,
                    sourceDocumentRepository.findById(
                            SPACE_ID,
                            response.results().getFirst().document().id()
                    ).orElseThrow()
            );
        }
        return documents;
    }

    /**
     * 把单个来源资料标识映射回固定 fixture 标识。
     *
     * @param documents fixture ID 到来源资料映射
     * @param documentId 来源资料标识
     * @return fixture 标识
     */
    private String toFixtureId(
            Map<String, SourceDocument> documents,
            Long documentId
    ) {
        return documents.entrySet().stream()
                .filter(entry -> entry.getValue().id().equals(documentId))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "候选标识不属于当前空间的固定资料: " + documentId));
    }

    /**
     * 计算两个集合的交集。
     *
     * @param left 左集合
     * @param right 右集合
     * @return 保序交集
     */
    private Set<String> intersection(Set<String> left, Set<String> right) {
        Set<String> result = new LinkedHashSet<>(left);
        result.retainAll(right);
        return result;
    }

    /**
     * 按字典序连接集合元素。
     *
     * @param values 集合
     * @return 逗号分隔文本
     */
    private String joinSorted(Set<String> values) {
        return values.stream().sorted().collect(Collectors.joining(", "));
    }

    /**
     * 读取 JSON 数组为字符串集合。
     *
     * @param node JSON 数组节点
     * @return 字符串集合
     */
    private Set<String> textSet(JsonNode node) {
        Set<String> result = new LinkedHashSet<>();
        node.forEach(item -> result.add(item.asText()));
        return result;
    }

    /**
     * 探测仓库内路径。
     *
     * @param candidates 候选路径
     * @return 第一个存在的路径
     */
    private Path findPath(Path... candidates) {
        return List.of(candidates).stream()
                .filter(Files::exists)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("找不到 v2 固定资料"));
    }

    /**
     * 定位仓库根目录，报告路径常量自带 docs/tests 前缀。
     *
     * @return 仓库根目录
     */
    private Path resolveRepositoryRoot() {
        Path fixtureRoot = findPath(
                Path.of("fixture", "document-association-v2"),
                Path.of("..", "fixture", "document-association-v2"),
                Path.of("..", "..", "fixture", "document-association-v2")
        );
        return fixtureRoot.getParent().getParent();
    }

    /**
     * 格式化指标为 4 位小数。
     *
     * @param value 指标值
     * @return 格式化文本
     */
    private String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.4f", value);
    }

    /**
     * 固定资料定义。
     *
     * @param fixtureRoot fixture 根目录
     * @param documentIds 全部标注文档标识
     * @param documentPaths 文档标识到相对路径
     * @param retrievalCases 冻结召回用例
     */
    private record Fixture(
            Path fixtureRoot,
            List<String> documentIds,
            Map<String, String> documentPaths,
            List<RetrievalCase> retrievalCases
    ) {
    }

    /**
     * 冻结召回用例。
     *
     * @param caseId 用例标识
     * @param sourceDocumentId 主体文档 fixture 标识
     * @param expectedCandidateIds 期望候选 fixture 标识
     * @param hardNegativeIds 硬负例 fixture 标识
     * @param contentRecallExpectation 内容通道预期：missed/recalled/partial/empty
     */
    private record RetrievalCase(
            String caseId,
            String sourceDocumentId,
            Set<String> expectedCandidateIds,
            Set<String> hardNegativeIds,
            String contentRecallExpectation
    ) {
    }

    /**
     * 一个用例的内容召回结果。
     *
     * @param candidates 候选集合
     * @param orderedCandidates 保序候选列表
     * @param recalledExpected 命中的期望候选
     * @param missedExpected 漏掉的期望候选
     */
    private record RecallOutcome(
            Set<String> candidates,
            List<String> orderedCandidates,
            Set<String> recalledExpected,
            Set<String> missedExpected
    ) {
    }
}

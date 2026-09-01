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
import static org.assertj.core.data.Offset.offset;

/**
 * v2 固定资料集上的语义分数阈值扫描实验（PRD 3.9）。
 *
 * <p>本轮唯一变量为语义文档级候选分数下限；所有阈值复用同一次真实向量化结果，
 * 不产生额外 Embedding 请求，也不修改任何默认候选链路。判定标准取 PRD 第 4 节
 * 接入门槛：融合 Recall@8 高于内容臂且恢复漏召回正例、Precision@8 不低于 0.1707、
 * 硬负例为 0、孤立文档语义候选为 0。</p>
 */
@Tag("real-ai")
@SpringBootTest(properties = {
        "app.upload-dir=target/test-data/document-association-v2-threshold-sweep-uploads",
        "ai.enabled=${TEST_REAL_EMBEDDING:false}",
        "ai.embedding-enabled=${TEST_REAL_EMBEDDING:false}"
})
class DocumentAssociationV2ThresholdSweepEvaluationTests {

    private static final Long SPACE_ID = TestKnowledgeSpaceFixtures.DEFAULT_SPACE_ID;
    private static final String DATASET_VERSION = "document-association-eval-v2";
    private static final String REPORT_FILE =
            "docs/tests/document-association-v2-threshold-sweep-real-qwen3.7-embedding-v1.md";
    private static final int FROZEN_TOP_K = 8;
    private static final int RRF_CONSTANT = ReciprocalRankFusion.RRF_CONSTANT;
    private static final double PRECISION_GATE = 0.1707D;

    /** 扫描的分数下限集合；上界延伸到 0.85 以覆盖 v2 相似度区间。 */
    private static final List<Double> THRESHOLDS = List.of(
            0.00D, 0.30D, 0.35D, 0.40D, 0.45D, 0.50D, 0.55D, 0.60D, 0.65D, 0.70D, 0.75D, 0.80D, 0.85D
    );

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

    @BeforeEach
    void clearData() {
        // 按依赖顺序清理阶段 3 事实和全部历史业务数据，保证扫描从干净事实库开始
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
    void sweepsThresholdsOnV2FixtureAndWritesReport() throws IOException {
        Fixture fixture = loadFixture();
        Map<String, SourceDocument> documents = importFixtureDocuments(fixture);

        // 第一轮：为全部资料建立章节、分片和真实向量事实
        Map<String, SemanticDocumentRecall> semanticRecalls = new LinkedHashMap<>();
        for (String fixtureId : documents.keySet()) {
            semanticRecalls.put(fixtureId, semanticRecallService.recall(
                    SPACE_ID,
                    documents.get(fixtureId).id()
            ));
        }

        // 第二轮：每个用例各召回一次，保留内容臂排名与语义臂带分数候选
        Map<String, CaseScores> caseScores = new LinkedHashMap<>();
        for (RetrievalCase retrievalCase : fixture.retrievalCases()) {
            DocumentCandidateRecall contentRecall = candidateRecallService.recall(
                    SPACE_ID,
                    documents.get(retrievalCase.sourceDocumentId()).id()
            );
            List<String> contentRanking = contentRecall.candidates().stream()
                    .map(candidate -> toFixtureId(documents, candidate.documentId()))
                    .toList();

            SemanticDocumentRecall semanticRecall = semanticRecallService.recall(
                    SPACE_ID,
                    documents.get(retrievalCase.sourceDocumentId()).id()
            );

            caseScores.put(retrievalCase.caseId(), new CaseScores(
                    retrievalCase.sourceDocumentId(),
                    contentRanking,
                    semanticRecall.candidates().stream()
                            .map(candidate -> new ScoredCandidate(
                                    toFixtureId(documents, candidate.sourceDocumentId()),
                                    candidate.bestChunkScore()
                            ))
                            .toList(),
                    retrievalCase.expectedCandidateIds(),
                    retrievalCase.hardNegativeIds(),
                    retrievalCase.caseId().endsWith("isolated-coffee")
            ));
        }

        // 内容臂指标与阈值无关
        Metric contentMetric = metric(caseScores, caseScores.keySet().stream()
                .collect(Collectors.toMap(
                        caseId -> caseId,
                        caseId -> new LinkedHashSet<>(caseScores.get(caseId).contentCandidates()),
                        (left, right) -> left,
                        LinkedHashMap::new
                )));

        // 每个阈值只改变分数下限一个变量
        Map<Double, SweepRow> sweepRows = new LinkedHashMap<>();
        for (Double threshold : THRESHOLDS) {
            Map<String, Set<String>> semanticByCase = new LinkedHashMap<>();
            Map<String, Set<String>> fusedByCase = new LinkedHashMap<>();
            for (Map.Entry<String, CaseScores> entry : caseScores.entrySet()) {
                CaseScores scores = entry.getValue();

                // 语义臂：只保留不低于阈值的候选，按既有分数排序
                List<String> semanticRanking = scores.semanticCandidates().stream()
                        .filter(candidate -> candidate.score() >= threshold)
                        .map(ScoredCandidate::fixtureId)
                        .toList();
                semanticByCase.put(entry.getKey(), new LinkedHashSet<>(semanticRanking));

                // 融合臂：内容排名与阈值过滤后的语义排名做 RRF 并集
                List<String> fusedRanking = ReciprocalRankFusion.fuse(
                        new ArrayList<>(scores.contentCandidates()),
                        semanticRanking,
                        RRF_CONSTANT,
                        FROZEN_TOP_K
                ).stream().map(ReciprocalRankFusion.FusedCandidate<String>::documentId).toList();
                fusedByCase.put(entry.getKey(), new LinkedHashSet<>(fusedRanking));
            }

            Metric semanticMetric = metric(caseScores, semanticByCase);
            Metric fusedMetric = metric(caseScores, fusedByCase);
            int isolatedSemanticCount = caseScores.entrySet().stream()
                    .filter(entry -> entry.getValue().isolatedCase())
                    .mapToInt(entry -> semanticByCase.get(entry.getKey()).size())
                    .sum();
            sweepRows.put(threshold, new SweepRow(
                    threshold, contentMetric, semanticMetric, fusedMetric, isolatedSemanticCount));
        }

        writeReport(sweepRows, semanticRecalls);

        // 无阈值基线必须复现 PRD 3.8 的 v2 真实对照结果
        SweepRow baseline = sweepRows.get(THRESHOLDS.getFirst());
        assertThat(baseline.contentMetric().recallAt8()).isCloseTo(0.3333D, offset(0.0005D));
        assertThat(baseline.fusedMetric().recallAt8()).isCloseTo(1.0D, offset(1e-9D));
        assertThat(baseline.fusedMetric().precisionAt8()).isCloseTo(0.1837D, offset(0.0005D));
        assertThat(baseline.fusedMetric().hardNegativeCount()).isEqualTo(2);
        assertThat(baseline.isolatedSemanticCount()).isEqualTo(5);

        // 任何阈值下都不允许自关联；空间边界由数据库向量事实复核
        sweepRows.values().forEach(row -> {
            assertThat(row.fusedMetric().selfAssociationCount()).isZero();
            assertThat(row.semanticMetric().selfAssociationCount()).isZero();
        });
        Integer crossSpaceVectors = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM document_chunk_index_states WHERE space_id <> ?",
                Integer.class,
                SPACE_ID
        );
        assertThat(crossSpaceVectors == null ? 0 : crossSpaceVectors).isZero();
    }

    /**
     * 汇总一条候选臂的冻结指标。
     *
     * @param caseScores 全部用例定义
     * @param byCase 该臂按用例的候选集合
     * @return 微平均指标
     */
    private Metric metric(
            Map<String, CaseScores> caseScores,
            Map<String, Set<String>> byCase
    ) {
        int expectedCount = 0;
        int recalledExpectedCount = 0;
        int candidateCount = 0;
        int hardNegativeCount = 0;
        int selfAssociationCount = 0;
        for (Map.Entry<String, CaseScores> entry : caseScores.entrySet()) {
            CaseScores scores = entry.getValue();
            Set<String> candidates = byCase.get(entry.getKey());
            expectedCount += scores.expectedCandidates().size();
            recalledExpectedCount += intersection(candidates, scores.expectedCandidates()).size();
            candidateCount += candidates.size();
            hardNegativeCount += intersection(candidates, scores.hardNegatives()).size();
            selfAssociationCount += candidates.contains(scores.selfDocumentId()) ? 1 : 0;
        }
        return new Metric(
                expectedCount == 0 ? 1.0 : recalledExpectedCount / (double) expectedCount,
                candidateCount == 0 ? 1.0 : recalledExpectedCount / (double) candidateCount,
                hardNegativeCount,
                selfAssociationCount,
                0
        );
    }

    /**
     * 判断一个扫描行是否满足 PRD 第 4 节全部门槛。
     *
     * @param row 扫描行
     * @param contentRecallAt8 内容臂 Recall@8
     * @return 全部门槛满足时返回 true
     */
    private boolean isQualified(SweepRow row, double contentRecallAt8) {
        return row.fusedMetric().recallAt8() > contentRecallAt8
                && row.fusedMetric().precisionAt8() >= PRECISION_GATE
                && row.fusedMetric().hardNegativeCount() == 0
                && row.isolatedSemanticCount() == 0;
    }

    /**
     * 将扫描结果写入仓库内固定报告。
     *
     * @param sweepRows 阈值到扫描行
     * @param semanticRecalls 语义召回原始结果（用于记录模型描述）
     * @throws IOException 报告无法写入时抛出
     */
    private void writeReport(
            Map<Double, SweepRow> sweepRows,
            Map<String, SemanticDocumentRecall> semanticRecalls
    ) throws IOException {
        String descriptor = semanticRecalls.values().iterator().next().descriptor().toString();
        double contentRecallAt8 = sweepRows.values().iterator().next().contentMetric().recallAt8();
        Path report = findPath(
                Path.of("fixture", "document-association-v2"),
                Path.of("..", "fixture", "document-association-v2"),
                Path.of("..", "..", "fixture", "document-association-v2")
        ).getParent().getParent().resolve(REPORT_FILE);
        StringBuilder content = new StringBuilder()
                .append("# v2 语义分数阈值扫描实验报告 v1（真实 qwen3.7-text-embedding）\n\n")
                .append("- datasetVersion：").append(DATASET_VERSION).append('\n')
                .append("- 内容候选召回：document-candidate-recall-v1，TopK=8\n")
                .append("- 语义候选召回：document-semantic-recall-v1，文档级 TopK=8，分片查询 TopK=8\n")
                .append("- 融合方式：RRF，constant=").append(RRF_CONSTANT).append("，TopK=8\n")
                .append("- 本轮唯一变量：语义文档级候选分数下限（bestChunkScore 阈值）\n")
                .append("- Embedding：真实 OpenAI-compatible（").append(descriptor).append("）\n")
                .append("- 运行方式：Java 21 + MySQL + 真实 Embedding + 精确 COSINE 扫描\n")
                .append("- 达标判定：融合 Recall@8 高于内容臂（").append(fmt(contentRecallAt8))
                .append("）且恢复漏召回正例、Precision@8 ≥ ").append(PRECISION_GATE)
                .append("、硬负例 0、孤立文档语义候选 0\n\n")
                .append("## 扫描结果\n\n")
                .append("| 阈值 | 内容 Recall@8 | 语义硬负例 | 融合 Recall@8 | 融合 Precision@8 | 融合硬负例 | 孤立文档语义候选 | 达标 |\n")
                .append("| ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |\n");
        sweepRows.values().forEach(row -> content
                .append("| ").append(String.format(java.util.Locale.ROOT, "%.2f", row.threshold()))
                .append(" | ").append(fmt(row.contentMetric().recallAt8()))
                .append(" | ").append(row.semanticMetric().hardNegativeCount())
                .append(" | ").append(fmt(row.fusedMetric().recallAt8()))
                .append(" | ").append(fmt(row.fusedMetric().precisionAt8()))
                .append(" | ").append(row.fusedMetric().hardNegativeCount())
                .append(" | ").append(row.isolatedSemanticCount())
                .append(" | ").append(isQualified(row, contentRecallAt8) ? "是" : "否")
                .append(" |\n"));
        content
                .append("\n## 达标判定\n\n");
        List<Double> qualifiedThresholds = sweepRows.entrySet().stream()
                .filter(entry -> isQualified(entry.getValue(), contentRecallAt8))
                .map(Map.Entry::getKey)
                .toList();
        if (qualifiedThresholds.isEmpty()) {
            content.append("扫描范围内没有任何阈值同时满足全部门槛条件；语义候选继续不接入默认链路。\n\n");
        } else {
            content.append("以下阈值首次同时满足全部门槛：")
                    .append(qualifiedThresholds.stream()
                            .map(threshold -> String.format(java.util.Locale.ROOT, "%.2f", threshold))
                            .collect(Collectors.joining("、")))
                    .append("。接入默认链路仍需按 PRD 第 4 节完成 includeSemanticCandidates 开关实现、")
                    .append("回滚验证与人工评估；本报告只回答指标问题。\n\n");
        }
        content
                .append("## 解释与边界\n\n")
                .append("本扫描只改变语义文档级候选分数下限一个变量，所有阈值复用同一次真实向量化结果，")
                .append("不产生额外 Embedding 请求，也不修改任何默认候选链路。内容臂与阈值无关。")
                .append("结论仅适用于当次端点、模型、版本和 v2 固定资料集；更换任一变量后必须重新扫描。")
                .append("本结果不代表大规模 ANN 性能、真实模型并发或生产部署质量。\n");
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
        JsonNode root = new ObjectMapper().readTree(
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
     * @param contentRecallExpectation 内容通道预期
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
     * 带分数的语义候选。
     *
     * @param fixtureId 候选文档 fixture 标识
     * @param score 文档级最高分片余弦相似度
     */
    private record ScoredCandidate(String fixtureId, double score) {
    }

    /**
     * 一个用例的带分数召回。
     *
     * @param contentCandidates 内容臂候选排名（与阈值无关）
     * @param semanticCandidates 语义臂带分数候选（按分数降序）
     * @param expectedCandidates 期望候选
     * @param hardNegatives 硬负例
     * @param isolatedCase 是否为孤立文档用例
     */
    private record CaseScores(
            String selfDocumentId,
            List<String> contentCandidates,
            List<ScoredCandidate> semanticCandidates,
            Set<String> expectedCandidates,
            Set<String> hardNegatives,
            boolean isolatedCase
    ) {
    }

    /**
     * 一条候选臂的冻结指标。
     *
     * @param recallAt8 微平均 Recall@8
     * @param precisionAt8 微平均 Precision@8
     * @param hardNegativeCount 硬负例命中数
     * @param selfAssociationCount 自关联数
     * @param crossSpaceCount 跨空间候选数
     */
    private record Metric(
            double recallAt8,
            double precisionAt8,
            int hardNegativeCount,
            int selfAssociationCount,
            int crossSpaceCount
    ) {
    }

    /**
     * 一个阈值下的扫描行。
     *
     * @param threshold 分数下限
     * @param contentMetric 内容臂指标（与阈值无关，重复记录便于阅读）
     * @param semanticMetric 语义臂指标
     * @param fusedMetric 融合臂指标
     * @param isolatedSemanticCount 孤立文档用例的语义候选数
     */
    private record SweepRow(
            double threshold,
            Metric contentMetric,
            Metric semanticMetric,
            Metric fusedMetric,
            int isolatedSemanticCount
    ) {
    }
}

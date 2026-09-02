package com.flevin.knowgraph.server.association;

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
 * 语义分数阈值扫描评估：固定资料集上每次只改变"语义文档级候选分数下限"一个变量。
 *
 * <p>该测试使用真实 DashScope Embedding 与精确 COSINE，在一次真实向量化运行后，
 * 对多个分数下限分别计算语义臂和 RRF 融合臂的 Recall@8、Precision@8、硬负例和
 * 孤立文档候选数，并写入仓库内扫描报告。阶段 3.5 真实对照未达接入门槛的主要表现为
 * 语义臂近似全召回且孤立文档产生候选，本实验用于回答"是否存在一个阈值能同时满足
 * Recall 不下降、Precision 不低于 0.1707 且硬负例为 0"；结果不修改任何默认候选链路。</p>
 */
@Tag("real-ai")
@SpringBootTest(properties = {
        "app.upload-dir=target/test-data/document-semantic-threshold-sweep-uploads",
        "ai.enabled=false",
        "ai.embedding-enabled=${TEST_REAL_EMBEDDING:false}"
})
class DocumentSemanticThresholdSweepEvaluationTests {

    private static final Long SPACE_ID = TestKnowledgeSpaceFixtures.DEFAULT_SPACE_ID;
    private static final String DATASET_VERSION = "document-association-eval-v1";
    private static final String REPORT_FILE = "docs/tests/document-association-semantic-threshold-sweep-real-qwen3.7-embedding-v1.md";
    private static final int FROZEN_TOP_K = 8;
    private static final int RRF_CONSTANT = ReciprocalRankFusion.RRF_CONSTANT;

    /** 扫描的分数下限集合；0.00 为无阈值基线，其余按真实余弦分数常见区间取样。 */
    private static final List<Double> THRESHOLDS = List.of(
            0.00D, 0.30D, 0.35D, 0.40D, 0.45D, 0.50D, 0.55D, 0.60D, 0.65D, 0.70D
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
    void sweepsSemanticScoreThresholdsOnFrozenFixtureAndWritesReport() throws IOException {
        Fixture fixture = loadFixture();
        Map<String, SourceDocument> documents = importFixtureDocuments();

        // 第一轮：为全部资料建立章节、分片和向量事实
        for (String fixtureId : documents.keySet()) {
            semanticRecallService.recall(SPACE_ID, documents.get(fixtureId).id());
        }

        // 第二轮：每个冻结用例各召回一次，内容臂与阈值无关；语义臂保留全部带分数候选
        Map<String, CaseScores> caseScores = new LinkedHashMap<>();
        for (RetrievalCase retrievalCase : fixture.retrievalCases()) {
            SourceDocument sourceDocument = documents.get(retrievalCase.sourceDocumentId());

            DocumentCandidateRecall contentRecall = candidateRecallService.recall(
                    SPACE_ID,
                    sourceDocument.id()
            );
            List<Long> contentRanking = contentRecall.candidates().stream()
                    .map(candidate -> candidate.documentId())
                    .toList();

            SemanticDocumentRecall semanticRecall = semanticRecallService.recall(
                    SPACE_ID,
                    sourceDocument.id()
            );

            caseScores.put(retrievalCase.caseId(), new CaseScores(
                    retrievalCase.sourceDocumentId(),
                    toFixtureIds(documents, contentRanking),
                    semanticRecall.candidates().stream()
                            .map(candidate -> new ScoredCandidate(
                                    toFixtureId(documents, candidate.sourceDocumentId()),
                                    candidate.bestChunkScore()
                            ))
                            .toList(),
                    retrievalCase.expectedCandidateIds(),
                    retrievalCase.hardNegativeIds(),
                    retrievalCase.caseId().endsWith("isolated-document")
            ));
        }

        // 每个阈值只改变分数下限一个变量；语义臂按过滤后顺序截取 TopK，融合臂复用冻结 RRF 常数
        Map<Double, SweepRow> sweepRows = new LinkedHashMap<>();
        for (Double threshold : THRESHOLDS) {
            sweepRows.put(threshold, sweep(caseScores, threshold));
        }

        writeReport(fixture, documents, sweepRows, caseScores);

        // 无阈值基线必须复现阶段 3.5 真实对照结果，保证扫描与既有报告可比
        SweepRow baseline = sweepRows.get(THRESHOLDS.getFirst());
        assertThat(baseline.semanticMetric().recallAt8()).isCloseTo(1.0D, offset(1e-9D));
        assertThat(baseline.semanticMetric().precisionAt8()).isCloseTo(0.1400D, offset(0.0005D));
        assertThat(baseline.semanticMetric().hardNegativeCount()).isEqualTo(4);
        assertThat(baseline.fusedMetric().precisionAt8()).isCloseTo(0.1373D, offset(0.0005D));

        // 任何阈值下都不允许出现自关联；语义候选全部来自本空间固定资料
        sweepRows.values().forEach(row -> {
            assertThat(row.semanticMetric().selfAssociationCount()).isZero();
            assertThat(row.fusedMetric().selfAssociationCount()).isZero();
        });
        Integer crossSpaceVectors = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM document_chunk_index_states WHERE space_id <> ?",
                Integer.class,
                SPACE_ID
        );
        assertThat(crossSpaceVectors == null ? 0 : crossSpaceVectors).isZero();
    }

    /**
     * 对一个分数下限计算语义臂和融合臂指标。
     *
     * @param caseScores 全部用例的带分数召回
     * @param threshold 语义文档级候选分数下限
     * @return 该阈值下的扫描行
     */
    private SweepRow sweep(Map<String, CaseScores> caseScores, double threshold) {
        Map<String, Set<String>> semanticByCase = new LinkedHashMap<>();
        Map<String, Set<String>> fusedByCase = new LinkedHashMap<>();
        for (Map.Entry<String, CaseScores> entry : caseScores.entrySet()) {
            CaseScores scores = entry.getValue();

            // 语义臂：只保留 bestChunkScore 不低于阈值的候选，按既有分数排序截取 TopK
            List<String> semanticRanking = scores.semanticCandidates().stream()
                    .filter(candidate -> candidate.score() >= threshold)
                    .map(ScoredCandidate::fixtureId)
                    .toList();
            semanticByCase.put(entry.getKey(), new LinkedHashSet<>(semanticRanking));

            // 融合臂：内容排名与阈值过滤后的语义排名做 RRF
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
        return new SweepRow(threshold, semanticMetric, fusedMetric, isolatedSemanticCount);
    }

    /**
     * 汇总一条候选臂的冻结指标。
     *
     * @param caseScores 全部用例定义
     * @param byCase 该臂按用例的候选集合
     * @return 微平均 Recall@8、Precision@8、硬负例、自关联和跨空间计数
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
     * 将扫描结果写入仓库内固定报告。
     *
     * @param fixture 固定资料定义
     * @param documents fixture ID 到来源资料映射
     * @param sweepRows 阈值到扫描行
     * @param caseScores 全部用例的带分数召回
     * @throws IOException 报告无法写入时抛出
     */
    private void writeReport(
            Fixture fixture,
            Map<String, SourceDocument> documents,
            Map<Double, SweepRow> sweepRows,
            Map<String, CaseScores> caseScores
    ) throws IOException {
        String descriptor = caseScores.values().iterator().next().semanticCandidates().isEmpty()
                ? "无候选"
                : semanticRecallService.recall(
                        SPACE_ID,
                        documents.get(fixture.retrievalCases().getFirst().sourceDocumentId()).id()
                ).descriptor().toString();
        // 报告固定写入仓库 docs/tests；fixtureRoot 锚定仓库根，避免模块工作目录差异
        Path report = fixture.fixtureRoot().getParent().getParent()
                .resolve(REPORT_FILE);
        StringBuilder content = new StringBuilder()
                .append("# 语义分数阈值扫描实验报告 v1（真实 qwen3.7-text-embedding）\n\n")
                .append("- datasetVersion：").append(DATASET_VERSION).append('\n')
                .append("- 内容候选召回：document-candidate-recall-v1，TopK=8\n")
                .append("- 语义候选召回：document-semantic-recall-v1，文档级 TopK=8，分片查询 TopK=8\n")
                .append("- 融合方式：RRF，constant=").append(RRF_CONSTANT).append("，TopK=8\n")
                .append("- 本轮唯一变量：语义文档级候选分数下限（bestChunkScore 阈值）\n")
                .append("- Embedding：真实 OpenAI-compatible（").append(descriptor).append("）\n")
                .append("- 运行方式：Java 21 + MySQL + 真实 Embedding + 精确 COSINE 扫描\n\n")
                .append("## 扫描结果\n\n")
                .append("| 阈值 | 语义 Recall@8 | 语义 Precision@8 | 语义硬负例 | 融合 Recall@8 | 融合 Precision@8 | 融合硬负例 | 孤立文档语义候选数 | 达标 |\n")
                .append("| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |\n");
        sweepRows.values().forEach(row -> content
                .append("| ").append(String.format(java.util.Locale.ROOT, "%.2f", row.threshold()))
                .append(" | ").append(fmt(row.semanticMetric().recallAt8()))
                .append(" | ").append(fmt(row.semanticMetric().precisionAt8()))
                .append(" | ").append(row.semanticMetric().hardNegativeCount())
                .append(" | ").append(fmt(row.fusedMetric().recallAt8()))
                .append(" | ").append(fmt(row.fusedMetric().precisionAt8()))
                .append(" | ").append(row.fusedMetric().hardNegativeCount())
                .append(" | ").append(row.isolatedSemanticCount())
                .append(" | ").append(isQualified(row) ? "是" : "否")
                .append(" |\n"));
        content
                .append("\n## 达标判定\n\n")
                .append("接入门槛取自 PRD 第 4 节：融合臂 Recall@8 不低于内容臂（1.0000）、Precision@8 不低于 0.1707、")
                .append("硬负例为 0 且孤立文档语义候选为 0。\n\n");
        SweepRow qualified = sweepRows.values().stream()
                .filter(this::isQualified)
                .findFirst()
                .orElse(null);
        if (qualified == null) {
            content.append("扫描范围内没有任何阈值同时满足全部门槛条件；语义候选继续不接入默认链路。\n\n");
        } else {
            content.append("阈值 ").append(String.format(java.util.Locale.ROOT, "%.2f", qualified.threshold()))
                    .append(" 首次满足全部指标门槛；但接入默认链路仍需按 PRD 第 4 节完成回滚验证与人工评估，本报告只回答指标问题。\n\n");
        }
        content
                .append("## 解释与边界\n\n")
                .append("本扫描只改变语义文档级候选分数下限一个变量，所有阈值复用同一次真实向量化结果，")
                .append("不产生额外 Embedding 请求，也不修改任何默认候选链路。内容臂与阈值无关，其基线为 ")
                .append("Recall@8=1.0000、Precision@8=0.1707。结论仅适用于当次端点、模型、版本和 12 份固定资料集；")
                .append("更换任一变量后必须重新扫描。本结果不代表大规模 ANN 性能、真实模型并发或生产部署质量。\n");
        Files.writeString(report, content.toString(), StandardCharsets.UTF_8);
    }

    /**
     * 判断一个扫描行是否满足 PRD 第 4 节指标门槛。
     *
     * @param row 扫描行
     * @return 全部指标门槛满足时返回 true
     */
    private boolean isQualified(SweepRow row) {
        return row.fusedMetric().recallAt8() >= 1.0D
                && row.fusedMetric().precisionAt8() >= 0.1707D
                && row.fusedMetric().hardNegativeCount() == 0
                && row.isolatedSemanticCount() == 0;
    }

    /**
     * 读取固定资料定义和机器可读标注。
     *
     * @return 固定资料评估输入
     * @throws IOException 标注无法读取时抛出
     */
    private Fixture loadFixture() throws IOException {
        Path fixtureRoot = findPath(
                Path.of("fixture", "document-association-v1"),
                Path.of("..", "fixture", "document-association-v1"),
                Path.of("..", "..", "fixture", "document-association-v1")
        );
        String annotations = Files.readString(fixtureRoot.resolve("annotations.json"));
        com.fasterxml.jackson.databind.JsonNode root =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(annotations);
        List<RetrievalCase> retrievalCases = new ArrayList<>();
        root.path("retrievalCases").forEach(node -> retrievalCases.add(new RetrievalCase(
                node.path("caseId").asText(),
                node.path("sourceDocumentId").asText(),
                textSet(node.path("expectedCandidateDocumentIds")),
                textSet(node.path("hardNegativeDocumentIds"))
        )));
        List<String> documentIds = new ArrayList<>();
        root.path("documents").forEach(node -> documentIds.add(node.path("documentId").asText()));
        return new Fixture(fixtureRoot, documentIds, retrievalCases);
    }

    /**
     * 通过正式导入链路导入 12 份冻结资料。
     *
     * @return fixture ID 到来源资料映射
     * @throws IOException 资料文件无法读取时抛出
     */
    private Map<String, SourceDocument> importFixtureDocuments() throws IOException {
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

        Path fixtureDirectory = findPath(
                Path.of("fixture", "document-association-v1", "documents"),
                Path.of("..", "fixture", "document-association-v1", "documents"),
                Path.of("..", "..", "fixture", "document-association-v1", "documents")
        );
        Map<String, SourceDocument> documents = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : fixtureFiles.entrySet()) {
            byte[] content = Files.readString(
                    fixtureDirectory.resolve(entry.getValue()),
                    StandardCharsets.UTF_8
            ).getBytes(StandardCharsets.UTF_8);
            MockMultipartFile file = new MockMultipartFile(
                    "files",
                    entry.getValue(),
                    "text/markdown",
                    content
            );
            DocumentImportResponse response = documentService.importDocuments(
                    SPACE_ID,
                    List.of(file)
            );
            documents.put(
                    entry.getKey(),
                    sourceDocumentRepository.findById(
                            SPACE_ID,
                            response.results().getFirst().document().id()
                    ).orElseThrow()
            );
        }
        return documents;
    }

    /**
     * 把来源资料标识列表映射回固定 fixture 标识集合。
     *
     * @param documents fixture ID 到来源资料映射
     * @param documentIds 候选标识列表
     * @return 有序 fixture 标识集合
     */
    private Set<String> toFixtureIds(
            Map<String, SourceDocument> documents,
            List<Long> documentIds
    ) {
        Set<String> fixtureIds = new LinkedHashSet<>();
        documentIds.forEach(documentId -> fixtureIds.add(toFixtureId(documents, documentId)));
        return fixtureIds;
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
    private Set<String> textSet(com.fasterxml.jackson.databind.JsonNode node) {
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
                .orElseThrow(() -> new IllegalStateException("找不到仓库内固定路径"));
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
     * @param retrievalCases 冻结召回用例
     */
    private record Fixture(
            Path fixtureRoot,
            List<String> documentIds,
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
     */
    private record RetrievalCase(
            String caseId,
            String sourceDocumentId,
            Set<String> expectedCandidateIds,
            Set<String> hardNegativeIds
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
     * @param selfDocumentId 主体文档 fixture 标识
     * @param contentCandidates 内容臂候选（与阈值无关）
     * @param semanticCandidates 语义臂带分数候选（按分数降序）
     * @param expectedCandidates 期望候选
     * @param hardNegatives 硬负例
     * @param isolatedCase 是否为孤立文档空召回用例
     */
    private record CaseScores(
            String selfDocumentId,
            Set<String> contentCandidates,
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
     * @param semanticMetric 语义臂指标
     * @param fusedMetric 融合臂指标
     * @param isolatedSemanticCount 孤立文档用例的语义候选数
     */
    private record SweepRow(
            double threshold,
            Metric semanticMetric,
            Metric fusedMetric,
            int isolatedSemanticCount
    ) {
    }
}

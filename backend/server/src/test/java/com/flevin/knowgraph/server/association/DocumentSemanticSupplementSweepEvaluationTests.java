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
 * 语义补集融合对照实验：固定资料集上每次只改变"融合方式"一个变量。
 *
 * <p>与 PRD 3.6 的 RRF 并集对照，本轮融合方式改为"内容臂优先 + 语义补集"：
 * 内容臂候选全部保留原序，语义臂只补充内容臂没有且分数不低于阈值的文档，截取 TopK=8。
 * 两种融合方式复用同一次真实向量化结果，逐阈值并排对照。该实验不改任何默认候选链路；
 * 结果用于回答"在内容臂已满召回的数据集上，补集通道是否存在精度收益"。</p>
 */
@Tag("real-ai")
@SpringBootTest(properties = {
        "app.upload-dir=target/test-data/document-semantic-supplement-sweep-uploads",
        "ai.enabled=false",
        "ai.embedding-enabled=${TEST_REAL_EMBEDDING:false}"
})
class DocumentSemanticSupplementSweepEvaluationTests {

    private static final Long SPACE_ID = TestKnowledgeSpaceFixtures.DEFAULT_SPACE_ID;
    private static final String DATASET_VERSION = "document-association-eval-v1";
    private static final String REPORT_FILE =
            "docs/tests/document-association-semantic-supplement-sweep-real-qwen3.7-embedding-v1.md";
    private static final int FROZEN_TOP_K = 8;
    private static final int RRF_CONSTANT = ReciprocalRankFusion.RRF_CONSTANT;

    /** 扫描的分数下限集合，与 PRD 3.6 保持一致以便逐行对照。 */
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
        // 按依赖顺序清理阶段 3 事实和全部历史业务数据，保证对照从干净事实库开始
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
    void sweepsSupplementFusionAgainstRrfUnionOnFrozenFixtureAndWritesReport() throws IOException {
        Fixture fixture = loadFixture();
        Map<String, SourceDocument> documents = importFixtureDocuments();

        // 第一轮：为全部资料建立章节、分片和向量事实
        for (String fixtureId : documents.keySet()) {
            semanticRecallService.recall(SPACE_ID, documents.get(fixtureId).id());
        }

        // 第二轮：每个冻结用例各召回一次，保留内容臂排名和语义臂带分数候选
        Map<String, CaseScores> caseScores = new LinkedHashMap<>();
        for (RetrievalCase retrievalCase : fixture.retrievalCases()) {
            SourceDocument sourceDocument = documents.get(retrievalCase.sourceDocumentId());

            DocumentCandidateRecall contentRecall = candidateRecallService.recall(
                    SPACE_ID,
                    sourceDocument.id()
            );
            List<String> contentRanking = contentRecall.candidates().stream()
                    .map(candidate -> toFixtureId(documents, candidate.documentId()))
                    .toList();

            SemanticDocumentRecall semanticRecall = semanticRecallService.recall(
                    SPACE_ID,
                    sourceDocument.id()
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
                    retrievalCase.caseId().endsWith("isolated-document")
            ));
        }

        // 每个阈值下同时计算 RRF 并集（对照基准，等价 PRD 3.6）与语义补集（本轮变量）
        Map<Double, SweepRow> sweepRows = new LinkedHashMap<>();
        for (Double threshold : THRESHOLDS) {
            sweepRows.put(threshold, sweep(caseScores, threshold));
        }

        writeReport(sweepRows);

        // 并集列必须复现 PRD 3.6 真实对照结果，保证两轮报告可比
        SweepRow baseline = sweepRows.get(THRESHOLDS.getFirst());
        assertThat(baseline.unionMetric().recallAt8()).isCloseTo(1.0D, offset(1e-9D));
        assertThat(baseline.unionMetric().precisionAt8()).isCloseTo(0.1373D, offset(0.0005D));
        assertThat(baseline.unionMetric().hardNegativeCount()).isEqualTo(4);

        // 任何融合方式、任何阈值下都不允许自关联；语义候选全部来自本空间固定资料
        sweepRows.values().forEach(row -> {
            assertThat(row.unionMetric().selfAssociationCount()).isZero();
            assertThat(row.supplementMetric().selfAssociationCount()).isZero();
        });
        Integer crossSpaceVectors = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM document_chunk_index_states WHERE space_id <> ?",
                Integer.class,
                SPACE_ID
        );
        assertThat(crossSpaceVectors == null ? 0 : crossSpaceVectors).isZero();
    }

    /**
     * 对一个分数下限计算 RRF 并集与语义补集两种融合的指标。
     *
     * @param caseScores 全部用例的带分数召回
     * @param threshold 语义文档级候选分数下限
     * @return 该阈值下的扫描行
     */
    private SweepRow sweep(Map<String, CaseScores> caseScores, double threshold) {
        Map<String, Set<String>> unionByCase = new LinkedHashMap<>();
        Map<String, Set<String>> supplementByCase = new LinkedHashMap<>();
        for (Map.Entry<String, CaseScores> entry : caseScores.entrySet()) {
            CaseScores scores = entry.getValue();

            // 阈值过滤后的语义排名，两种融合共用同一输入
            List<String> semanticRanking = scores.semanticCandidates().stream()
                    .filter(candidate -> candidate.score() >= threshold)
                    .map(ScoredCandidate::fixtureId)
                    .toList();

            // RRF 并集：与 PRD 3.6 完全相同的对照基准
            List<String> unionRanking = ReciprocalRankFusion.fuse(
                    new ArrayList<>(scores.contentCandidates()),
                    semanticRanking,
                    RRF_CONSTANT,
                    FROZEN_TOP_K
            ).stream().map(ReciprocalRankFusion.FusedCandidate<String>::documentId).toList();
            unionByCase.put(entry.getKey(), new LinkedHashSet<>(unionRanking));

            // 语义补集：内容臂候选全部保留原序，语义只补充内容臂没有的文档
            List<String> supplementRanking = new ArrayList<>(scores.contentCandidates());
            for (String fixtureId : semanticRanking) {
                if (!supplementRanking.contains(fixtureId)) {
                    supplementRanking.add(fixtureId);
                }
            }
            supplementByCase.put(entry.getKey(), new LinkedHashSet<>(
                    supplementRanking.subList(0, Math.min(FROZEN_TOP_K, supplementRanking.size()))));
        }

        Metric unionMetric = metric(caseScores, unionByCase);
        Metric supplementMetric = metric(caseScores, supplementByCase);
        int isolatedUnionCount = isolatedCount(caseScores, unionByCase);
        int isolatedSupplementCount = isolatedCount(caseScores, supplementByCase);
        return new SweepRow(threshold, unionMetric, supplementMetric,
                isolatedUnionCount, isolatedSupplementCount);
    }

    /**
     * 统计孤立文档用例在一条候选臂下的候选数。
     *
     * @param caseScores 全部用例定义
     * @param byCase 该臂按用例的候选集合
     * @return 孤立文档用例候选数
     */
    private int isolatedCount(
            Map<String, CaseScores> caseScores,
            Map<String, Set<String>> byCase
    ) {
        return caseScores.entrySet().stream()
                .filter(entry -> entry.getValue().isolatedCase())
                .mapToInt(entry -> byCase.get(entry.getKey()).size())
                .sum();
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
     * 将对照结果写入仓库内固定报告。
     *
     * @param sweepRows 阈值到扫描行
     * @throws IOException 报告无法写入时抛出
     */
    private void writeReport(Map<Double, SweepRow> sweepRows) throws IOException {
        Path report = findPath(
                Path.of("fixture", "document-association-v1"),
                Path.of("..", "fixture", "document-association-v1"),
                Path.of("..", "..", "fixture", "document-association-v1")
        ).getParent().getParent().resolve(REPORT_FILE);
        StringBuilder content = new StringBuilder()
                .append("# 语义补集融合对照实验报告 v1（真实 qwen3.7-text-embedding）\n\n")
                .append("- datasetVersion：").append(DATASET_VERSION).append('\n')
                .append("- 内容候选召回：document-candidate-recall-v1，TopK=8\n")
                .append("- 语义候选召回：document-semantic-recall-v1，文档级 TopK=8，分片查询 TopK=8\n")
                .append("- 本轮唯一变量：融合方式 = 内容臂优先 + 语义补集（对照 PRD 3.6 的 RRF 并集）\n")
                .append("- 补集定义：内容臂候选全部保留原序，语义只补充内容臂没有且分数不低于阈值的文档\n")
                .append("- 运行方式：Java 21 + MySQL + 真实 Embedding + 精确 COSINE 扫描\n")
                .append("- 说明：两种融合复用同一次真实向量化结果，RRF 并集列与 PRD 3.6 报告逐值一致\n\n")
                .append("## 扫描结果\n\n")
                .append("| 阈值 | 并集 Recall@8 | 并集 Precision@8 | 并集硬负例 | 补集 Recall@8 | 补集 Precision@8 | 补集硬负例 | 孤立文档候选（并集/补集） | 补集达标 |\n")
                .append("| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |\n");
        sweepRows.values().forEach(row -> content
                .append("| ").append(String.format(java.util.Locale.ROOT, "%.2f", row.threshold()))
                .append(" | ").append(fmt(row.unionMetric().recallAt8()))
                .append(" | ").append(fmt(row.unionMetric().precisionAt8()))
                .append(" | ").append(row.unionMetric().hardNegativeCount())
                .append(" | ").append(fmt(row.supplementMetric().recallAt8()))
                .append(" | ").append(fmt(row.supplementMetric().precisionAt8()))
                .append(" | ").append(row.supplementMetric().hardNegativeCount())
                .append(" | ").append(row.isolatedUnionCount()).append(" / ").append(row.isolatedSupplementCount())
                .append(" | ").append(isQualified(row) ? "是" : "否")
                .append(" |\n"));
        content
                .append("\n## 达标判定\n\n")
                .append("接入门槛取自 PRD 第 4 节：融合臂 Recall@8 不低于内容臂（1.0000）、Precision@8 不低于 0.1707、")
                .append("硬负例为 0 且孤立文档候选为 0。\n\n");
        SweepRow qualified = sweepRows.values().stream()
                .filter(this::isQualified)
                .findFirst()
                .orElse(null);
        if (qualified == null) {
            content.append("扫描范围内没有任何阈值使补集融合同时满足全部门槛条件；语义候选继续不接入默认链路。\n\n");
        } else {
            content.append("阈值 ").append(String.format(java.util.Locale.ROOT, "%.2f", qualified.threshold()))
                    .append(" 首次满足全部指标门槛；但接入默认链路仍需按 PRD 第 4 节完成回滚验证与人工评估，本报告只回答指标问题。\n\n");
        }
        content
                .append("## 结论与下一步\n\n")
                .append(conclusion(sweepRows))
                .append('\n')
                .append("## 解释与边界\n\n")
                .append("本扫描只改变融合方式一个变量，所有融合复用同一次真实向量化结果，不产生额外 Embedding 请求，")
                .append("也不修改任何默认候选链路。内容臂与融合方式无关，其基线为 Recall@8=1.0000、Precision@8=0.1707（7/41）。")
                .append("结论仅适用于当次端点、模型、版本和 12 份固定资料集；更换任一变量后必须重新扫描。")
                .append("本结果不代表大规模 ANN 性能、真实模型并发或生产部署质量。\n");
        Files.writeString(report, content.toString(), StandardCharsets.UTF_8);
    }

    /**
     * 依据对照数据生成结论段落。
     *
     * @param sweepRows 阈值到扫描行
     * @return 结论文本
     */
    private String conclusion(Map<Double, SweepRow> sweepRows) {
        SweepRow best = sweepRows.values().stream()
                .reduce((left, right) -> right.supplementMetric().precisionAt8()
                        >= left.supplementMetric().precisionAt8() ? right : left)
                .orElseThrow();
        StringBuilder conclusion = new StringBuilder()
                .append("补集融合在各阈值下的 Precision@8 均不高于内容臂基线 0.1707；最佳阈值 ")
                .append(String.format(java.util.Locale.ROOT, "%.2f", best.threshold()))
                .append(" 下补集 Precision@8 = ").append(fmt(best.supplementMetric().precisionAt8()))
                .append("、硬负例 ").append(best.supplementMetric().hardNegativeCount())
                .append("。这符合结构性预期：内容臂 Recall@8 已达 1.0000，")
                .append("不存在可补的内容漏召回正例，补集通道只会扩大候选分母而不会增加命中。\n\n")
                .append("结合 PRD 3.6 的阈值扫描可以得出：在 document-association-eval-v1 这份内容臂已满召回的小语料上，")
                .append("并集 RRF 与补集两种融合方式都无法满足接入门槛；语义召回的价值要在包含内容漏召回正例、")
                .append("或候选噪声更大的资料集上才能体现。下一步不是继续调融合参数，而是扩充冻结评估集（新增内容漏召回正例场景），")
                .append("或把语义候选作为实验能力保留、不进入默认链路。\n");
        return conclusion.toString();
    }

    /**
     * 判断一个扫描行的补集融合是否满足 PRD 第 4 节指标门槛。
     *
     * @param row 扫描行
     * @return 全部指标门槛满足时返回 true
     */
    private boolean isQualified(SweepRow row) {
        return row.supplementMetric().recallAt8() >= 1.0D
                && row.supplementMetric().precisionAt8() >= 0.1707D
                && row.supplementMetric().hardNegativeCount() == 0
                && row.isolatedSupplementCount() == 0;
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
     * @param contentCandidates 内容臂候选排名（与融合方式无关）
     * @param semanticCandidates 语义臂带分数候选（按分数降序）
     * @param expectedCandidates 期望候选
     * @param hardNegatives 硬负例
     * @param isolatedCase 是否为孤立文档空召回用例
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
     * @param unionMetric RRF 并集融合指标（对照基准）
     * @param supplementMetric 语义补集融合指标（本轮变量）
     * @param isolatedUnionCount 孤立文档用例在并集下的候选数
     * @param isolatedSupplementCount 孤立文档用例在补集下的候选数
     */
    private record SweepRow(
            double threshold,
            Metric unionMetric,
            Metric supplementMetric,
            int isolatedUnionCount,
            int isolatedSupplementCount
    ) {
    }
}

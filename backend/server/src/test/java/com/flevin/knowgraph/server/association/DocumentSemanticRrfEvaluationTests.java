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
import static org.assertj.core.data.Offset.offset;

/**
 * 阶段 3 独立语义召回与 RRF 对照固定资料评估。
 *
 * <p>该测试在真实 MySQL 事实库上使用 Fake Embedding 和精确 COSINE，对 7 个冻结召回用例
 * 分别计算内容候选、语义候选和 RRF 融合候选的 Recall@8、Precision@8、硬负例、自关联和
 * 跨空间候选，并把结果写入仓库内实验报告。Fake 向量排序不代表真实语义质量；本结果只证明
 * 数据流和对照链路，不构成语义候选接入默认链路的决策依据。</p>
 */
@SpringBootTest(properties = {
        "app.upload-dir=target/test-data/document-semantic-rrf-evaluation-uploads",
        // 默认关闭聊天 AI；显式环境变量 TEST_REAL_EMBEDDING=true 时只启用真实 Embedding
        "ai.enabled=false",
        "ai.embedding-enabled=${TEST_REAL_EMBEDDING:false}",
        // 报告路径相对仓库根目录；真实对照必须显式改名，避免覆盖 Fake 基线报告
        "test.semantic-rrf-report-name=${TEST_SEMANTIC_RRF_REPORT_NAME:docs/tests/document-association-semantic-rrf-evaluation-v1.md}"
})
class DocumentSemanticRrfEvaluationTests {

    private static final Long SPACE_ID = TestKnowledgeSpaceFixtures.DEFAULT_SPACE_ID;
    private static final String DATASET_VERSION = "document-association-eval-v1";
    private static final int FROZEN_TOP_K = 8;
    private static final int RRF_CONSTANT = ReciprocalRankFusion.RRF_CONSTANT;

    @Value("${ai.embedding-enabled:false}")
    private boolean realEmbeddingEnabled;

    @Value("${test.semantic-rrf-report-name:docs/tests/document-association-semantic-rrf-evaluation-v1.md}")
    private String reportFileName;

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
    void evaluatesSemanticRecallAndRrfFusionOnFrozenFixtureAndWritesReport() throws IOException {
        // 真实模式使用配置文件中的 Embedding 独立端点；配置缺失会在装配阶段直接失败
        Fixture fixture = loadFixture();
        Map<String, SourceDocument> documents = importFixtureDocuments();

        // 第一轮：为全部资料建立章节、分片和向量事实，记录向量生成耗时与规模
        long indexStartNanos = System.nanoTime();
        Map<String, SemanticDocumentRecall> warmupRecalls = new LinkedHashMap<>();
        for (String fixtureId : documents.keySet()) {
            warmupRecalls.put(fixtureId, semanticRecallService.recall(SPACE_ID, documents.get(fixtureId).id()));
        }
        long indexNanos = System.nanoTime() - indexStartNanos;

        Integer vectorCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM document_chunk_index_states WHERE space_id = ?",
                Integer.class,
                SPACE_ID
        );
        Integer chunkCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM document_chunks WHERE space_id = ?",
                Integer.class,
                SPACE_ID
        );

        // 第二轮：空间内向量已齐备，对每个冻结用例计算三条候选臂并计时语义查询
        long queryNanos = 0L;
        Map<String, ArmResult> results = new LinkedHashMap<>();
        for (RetrievalCase retrievalCase : fixture.retrievalCases()) {
            SourceDocument sourceDocument = documents.get(retrievalCase.sourceDocumentId());

            // 内容臂：冻结的 document-candidate-recall-v1 默认通道
            DocumentCandidateRecall contentRecall = candidateRecallService.recall(
                    SPACE_ID,
                    sourceDocument.id()
            );
            List<Long> contentRanking = contentRecall.candidates().stream()
                    .map(candidate -> candidate.documentId())
                    .toList();

            // 语义臂：独立语义召回，查询复用已存储的主体分片向量
            long recallStartNanos = System.nanoTime();
            SemanticDocumentRecall semanticRecall = semanticRecallService.recall(
                    SPACE_ID,
                    sourceDocument.id()
            );
            queryNanos += System.nanoTime() - recallStartNanos;
            List<Long> semanticRanking = semanticRecall.candidates().stream()
                    .map(candidate -> candidate.sourceDocumentId())
                    .toList();

            // 融合臂：RRF 只使用两路排名，常数冻结为 60
            List<Long> fusedRanking = ReciprocalRankFusion.fuse(
                    contentRanking,
                    semanticRanking,
                    RRF_CONSTANT,
                    FROZEN_TOP_K
            ).stream().map(ReciprocalRankFusion.FusedCandidate::documentId).toList();

            results.put(retrievalCase.caseId(), new ArmResult(
                    retrievalCase.sourceDocumentId(),
                    toFixtureIds(documents, contentRanking),
                    toFixtureIds(documents, semanticRanking),
                    toFixtureIds(documents, fusedRanking),
                    retrievalCase.expectedCandidateIds(),
                    retrievalCase.hardNegativeIds(),
                    semanticRecall.descriptor().toString()
            ));
        }

        Metric contentMetric = metric(results, ArmResult::contentCandidates);
        Metric semanticMetric = metric(results, ArmResult::semanticCandidates);
        Metric fusedMetric = metric(results, ArmResult::fusedCandidates);

        // 语义召回必须确定可重复：同一主体在向量齐备后的两次召回结果一致
        RetrievalCase firstCase = fixture.retrievalCases().getFirst();
        SemanticDocumentRecall repeatedRecall = semanticRecallService.recall(
                SPACE_ID,
                documents.get(firstCase.sourceDocumentId()).id()
        );
        assertThat(toFixtureIds(documents, repeatedRecall.candidates().stream()
                .map(candidate -> candidate.sourceDocumentId())
                .toList()))
                .isEqualTo(results.get(firstCase.caseId()).semanticCandidates());

        writeReport(
                fixture,
                documents,
                results,
                contentMetric,
                semanticMetric,
                fusedMetric,
                vectorCount == null ? 0 : vectorCount,
                chunkCount == null ? 0 : chunkCount,
                indexNanos,
                queryNanos
        );

        // 内容臂必须复现 v1 基线：全部 7 个期望候选进入前 8，Precision@8 保持 0.1707 噪声水平
        assertThat(contentMetric.recallAt8()).isCloseTo(1.0D, offset(1e-9D));
        assertThat(contentMetric.precisionAt8()).isCloseTo(0.1707D, offset(0.0005D));

        // 三条候选臂都不得出现自关联；空间边界由数据库向量事实复核
        assertThat(fusedMetric.selfAssociationCount()).isZero();
        assertThat(semanticMetric.selfAssociationCount()).isZero();
        assertThat(contentMetric.selfAssociationCount()).isZero();
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
     * @param results 全部用例结果
     * @param armCandidates 候选臂取值函数
     * @return 微平均 Recall@8、Precision@8、硬负例命中、自关联和跨空间计数
     */
    private Metric metric(
            Map<String, ArmResult> results,
            java.util.function.Function<ArmResult, Set<String>> armCandidates
    ) {
        int expectedCount = 0;
        int recalledExpectedCount = 0;
        int candidateCount = 0;
        int hardNegativeCount = 0;
        int selfAssociationCount = 0;
        int crossSpaceCount = 0;
        for (ArmResult result : results.values()) {
            Set<String> candidates = armCandidates.apply(result);
            // 全部候选都映射自本空间导入资料；映射失败会直接抛出，跨空间按 0 计
            expectedCount += result.expectedCandidates().size();
            recalledExpectedCount += intersection(candidates, result.expectedCandidates()).size();
            candidateCount += candidates.size();
            hardNegativeCount += intersection(candidates, result.hardNegatives()).size();
            selfAssociationCount += candidates.contains(result.selfDocumentId()) ? 1 : 0;
        }
        return new Metric(
                expectedCount == 0 ? 1.0 : recalledExpectedCount / (double) expectedCount,
                candidateCount == 0 ? 1.0 : recalledExpectedCount / (double) candidateCount,
                hardNegativeCount,
                selfAssociationCount,
                crossSpaceCount
        );
    }

    /**
     * 将实验结果写入仓库内固定报告。
     *
     * @param fixture 固定资料定义
     * @param documents fixture ID 到来源资料映射
     * @param results 全部用例结果
     * @param contentMetric 内容臂指标
     * @param semanticMetric 语义臂指标
     * @param fusedMetric 融合臂指标
     * @param vectorCount 向量事实总数
     * @param chunkCount 分片事实总数
     * @param indexNanos 向量建立阶段耗时（纳秒）
     * @param queryNanos 语义查询累计耗时（纳秒）
     * @throws IOException 报告无法写入时抛出
     */
    private void writeReport(
            Fixture fixture,
            Map<String, SourceDocument> documents,
            Map<String, ArmResult> results,
            Metric contentMetric,
            Metric semanticMetric,
            Metric fusedMetric,
            int vectorCount,
            int chunkCount,
            long indexNanos,
            long queryNanos
    ) throws IOException {
        String chunkVersion = semanticRecallService.recall(
                SPACE_ID,
                documents.get(fixture.retrievalCases().getFirst().sourceDocumentId()).id()
        ).chunkVersion();
        String descriptor = results.values().iterator().next().descriptor();
        // 真实评估必须确认为真实客户端，避免配置缺失时把 Fake 结果写成真实报告
        assertRealEmbeddingDescriptorWhenEnabled(descriptor);
        Path report = fixture.fixtureRoot().getParent().getParent().resolve(reportFileName);
        StringBuilder content = new StringBuilder()
                .append("# 文档关联独立语义召回与 RRF 对照实验报告 v1\n\n")
                .append("- datasetVersion：").append(DATASET_VERSION).append('\n')
                .append("- 内容候选召回：document-candidate-recall-v1，TopK=8\n")
                .append("- 语义候选召回：document-semantic-recall-v1，文档级 TopK=8，分片查询 TopK=8\n")
                .append("- 融合方式：RRF，constant=").append(RRF_CONSTANT).append("，TopK=8\n")
                .append("- 分片策略版本：").append(chunkVersion).append('\n')
                .append("- Embedding：")
                .append(realEmbeddingEnabled ? "真实 OpenAI-compatible" : "Fake")
                .append("（").append(descriptor).append("）\n")
                .append("- 运行方式：Java 21 + MySQL + ")
                .append(realEmbeddingEnabled ? "真实 OpenAI-compatible Embedding" : "确定性 Fake Embedding")
                .append(" + 精确 COSINE 扫描\n")
                .append("- 计分范围：7 个冻结召回用例、7 个期望候选；未标注文档对不计分\n\n")
                .append("## 指标\n\n")
                .append("| 指标 | 内容臂 | 语义臂 | 内容+语义 RRF 融合臂 |\n| --- | ---: | ---: | ---: |\n")
                .append("| Recall@8（微平均） | ").append(fmt(contentMetric.recallAt8()))
                .append(" | ").append(fmt(semanticMetric.recallAt8()))
                .append(" | ").append(fmt(fusedMetric.recallAt8())).append(" |\n")
                .append("| Precision@8（微平均） | ").append(fmt(contentMetric.precisionAt8()))
                .append(" | ").append(fmt(semanticMetric.precisionAt8()))
                .append(" | ").append(fmt(fusedMetric.precisionAt8())).append(" |\n")
                .append("| 硬负例命中数 | ").append(contentMetric.hardNegativeCount())
                .append(" | ").append(semanticMetric.hardNegativeCount())
                .append(" | ").append(fusedMetric.hardNegativeCount()).append(" |\n")
                .append("| 自关联数 | ").append(contentMetric.selfAssociationCount())
                .append(" | ").append(semanticMetric.selfAssociationCount())
                .append(" | ").append(fusedMetric.selfAssociationCount()).append(" |\n")
                .append("| 跨空间候选数 | ").append(contentMetric.crossSpaceCount())
                .append(" | ").append(semanticMetric.crossSpaceCount())
                .append(" | ").append(fusedMetric.crossSpaceCount()).append(" |\n\n")
                .append("## 向量索引与耗时\n\n")
                .append("- 分片事实总数：").append(chunkCount).append('\n')
                .append("- 向量事实总数：").append(vectorCount)
                .append("（均可从 MySQL 章节/分片事实重建）\n")
                .append("- 批量 Embedding 请求次数：").append(documents.size())
                .append("（每份资料 1 次批量请求，仅补缺失向量）\n")
                .append("- 向量建立阶段总耗时：").append(indexNanos / 1_000_000).append(" ms\n")
                .append("- 7 次语义查询总耗时：").append(queryNanos / 1_000_000)
                .append(" ms（查询复用已存储向量，0 次新 Embedding 请求）\n\n")
                .append("## 召回用例\n\n")
                .append("| caseId | 期望候选 | 内容臂 | 语义臂 | 融合臂 | 硬负例命中（内容/语义/融合） |\n")
                .append("| --- | --- | --- | --- | --- | --- |\n");
        results.forEach((caseId, result) -> content
                .append("| ").append(caseId)
                .append(" | ").append(joinSorted(result.expectedCandidates()))
                .append(" | ").append(joinSorted(result.contentCandidates()))
                .append(" | ").append(joinSorted(result.semanticCandidates()))
                .append(" | ").append(joinSorted(result.fusedCandidates()))
                .append(" | ")
                .append(joinSorted(intersection(result.contentCandidates(), result.hardNegatives())))
                .append(" / ")
                .append(joinSorted(intersection(result.semanticCandidates(), result.hardNegatives())))
                .append(" / ")
                .append(joinSorted(intersection(result.fusedCandidates(), result.hardNegatives())))
                .append(" |\n"));
        content
                .append("\n## 结论与下一步\n\n")
                .append(conclusion(contentMetric, semanticMetric, fusedMetric))
                .append('\n')
                .append("## 解释与边界\n\n")
                .append(realEmbeddingEnabled
                        ? "本报告使用真实 OpenAI-compatible Embedding 的显式对照，结论仅适用于当次端点、模型、版本和固定资料集；更换任一变量后必须重新评估。"
                        : "本报告使用确定性 Fake Embedding（字符哈希，固定维度），分片级相似度只反映字符分布，"
                          + "不代表真实语义相关性；因此三条候选臂的质量差异不能作为语义候选接入默认文档关联链路的依据。")
                .append("按阶段 3 接入门槛，只有满足 ")
                .append("Recall@8 不下降、恢复至少 1 个内容漏召回正例、硬负例与自关联为 0、Precision@8 不低于 0.1707 ")
                .append("且命中可反查 MySQL 分片时，才另行评估 includeSemanticCandidates。")
                .append("本结果也不代表大规模 ANN 性能、真实模型并发或生产部署质量。\n");
        Files.writeString(report, content.toString(), StandardCharsets.UTF_8);
    }

    /**
     * 依据三条候选臂指标生成结论段落。
     *
     * @param contentMetric 内容臂指标
     * @param semanticMetric 语义臂指标
     * @param fusedMetric 融合臂指标
     * @return 结论文本
     */
    private String conclusion(
            Metric contentMetric,
            Metric semanticMetric,
            Metric fusedMetric
    ) {
        boolean fusedRecallNotWorse = fusedMetric.recallAt8() >= contentMetric.recallAt8();
        boolean fusedPrecisionNotWorse = fusedMetric.precisionAt8() >= contentMetric.precisionAt8();
        boolean fusedHardNegativeClean = fusedMetric.hardNegativeCount() <= contentMetric.hardNegativeCount();
        String embeddingLabel = realEmbeddingEnabled ? "真实 Embedding" : "Fake Embedding";
        StringBuilder conclusion = new StringBuilder()
                .append("内容臂复现 v1 基线：Recall@8 = ").append(fmt(contentMetric.recallAt8()))
                .append("，Precision@8 = ").append(fmt(contentMetric.precisionAt8()))
                .append("（对照基线 1.0000 / 0.1707）。")
                .append(embeddingLabel).append("下语义臂 Recall@8 = ").append(fmt(semanticMetric.recallAt8()))
                .append("，融合臂 Recall@8 = ").append(fmt(fusedMetric.recallAt8()))
                .append("、Precision@8 = ").append(fmt(fusedMetric.precisionAt8()))
                .append("；融合臂自关联 ").append(fusedMetric.selfAssociationCount())
                .append("、跨空间候选 ").append(fusedMetric.crossSpaceCount())
                .append("，边界约束全部满足。\n\n");
        if (fusedRecallNotWorse && fusedPrecisionNotWorse && fusedHardNegativeClean) {
            conclusion.append("在").append(embeddingLabel).append("下融合臂相对内容臂的指标未变差；")
                    .append(realEmbeddingEnabled
                            ? "该结果满足阶段 3 接入门槛的指标条件，但接入默认候选仍需按 PRD 第 4 节完成回滚验证与人工评估。"
                            : "该结论只说明 RRF 融合数据流工作正常，不能外推到真实模型。下一开发切片为真实 Embedding 显式对照（阶段 3.5）。")
                    .append('\n');
        } else {
            conclusion.append("在").append(embeddingLabel).append("下融合臂出现指标变化（召回变差：").append(!fusedRecallNotWorse)
                    .append("，精确率变差：").append(!fusedPrecisionNotWorse)
                    .append("，硬负例增加：").append(!fusedHardNegativeClean)
                    .append("）；")
                    .append(realEmbeddingEnabled
                            ? "真实 Embedding 对照未达到阶段 3 接入门槛，按 PRD 第 4 节保留实验报告，默认内容召回继续运行，不接入语义候选。"
                            : "这符合字符哈希向量无语义的预期，说明 RRF 对语义臂排名敏感，真实 Embedding 对照（阶段 3.5）才能给出质量结论。")
                    .append('\n');
        }
        return conclusion.toString();
    }

    /**
     * 校验真实评估模式实际使用的 Embedding 客户端类型。
     *
     * @param descriptor 本次向量化客户端描述
     */
    private void assertRealEmbeddingDescriptorWhenEnabled(String descriptor) {
        if (!realEmbeddingEnabled) {
            return;
        }

        // 真实模式必须由 OpenAI-compatible 客户端提供，防止报告误标真实模型结果
        assertThat(descriptor)
                .contains("provider=openai-compatible")
                .doesNotContain("provider=fake");
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
     * 通过正式导入链路导入 12 份冻结资料，建立 fixture ID 到来源资料映射。
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
            // 读取固定虚构原文，通过正式导入链路生成内容指纹和空间记录
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
     * 把候选来源资料标识列表映射回固定 fixture 标识。
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
     * 按字典序连接集合元素。
     *
     * @param values 集合
     * @return 逗号分隔文本；空集合返回空字符串
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
    private Set<String> textSet(com.fasterxml.jackson.databind.JsonNode node) {
        Set<String> result = new LinkedHashSet<>();
        node.forEach(item -> result.add(item.asText()));
        return result;
    }

    /**
     * 探测仓库内固定 fixture 路径。
     *
     * @param candidates 候选路径
     * @return 第一个存在的路径
     */
    private Path findPath(Path... candidates) {
        return List.of(candidates).stream()
                .filter(Files::exists)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("找不到固定文档关联资料"));
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
     * 一个用例的三条候选臂结果。
     *
     * @param selfDocumentId 主体文档 fixture 标识
     * @param contentCandidates 内容臂候选
     * @param semanticCandidates 语义臂候选
     * @param fusedCandidates 融合臂候选
     * @param expectedCandidates 期望候选
     * @param hardNegatives 硬负例
     * @param descriptor 语义召回模型描述
     */
    private record ArmResult(
            String selfDocumentId,
            Set<String> contentCandidates,
            Set<String> semanticCandidates,
            Set<String> fusedCandidates,
            Set<String> expectedCandidates,
            Set<String> hardNegatives,
            String descriptor
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
}

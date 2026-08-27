package com.flevin.knowgraph.server.association;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flevin.knowgraph.server.KnowledgeGraphApplication;
import com.flevin.knowgraph.server.model.association.DocumentAssociationCandidateContext;
import com.flevin.knowgraph.server.model.association.DocumentAssociationDecision;
import com.flevin.knowgraph.server.model.association.DocumentAssociationDocumentContext;
import com.flevin.knowgraph.server.model.association.DocumentAssociationEvidenceCandidate;
import com.flevin.knowgraph.server.model.association.DocumentAssociationRequest;
import com.flevin.knowgraph.server.model.association.DocumentAssociationResult;
import com.flevin.knowgraph.server.model.association.DocumentAssociationRunResponse;
import com.flevin.knowgraph.server.model.association.DocumentCandidateRecall;
import com.flevin.knowgraph.server.model.association.DocumentRelationResponse;
import com.flevin.knowgraph.server.model.ai.rag.DocumentChunk;
import com.flevin.knowgraph.server.model.document.DocumentImportResponse;
import com.flevin.knowgraph.server.model.document.SourceDocument;
import com.flevin.knowgraph.server.repository.document.SourceDocumentRepository;
import com.flevin.knowgraph.server.service.ai.rag.PrdMarkdownSectionParser;
import com.flevin.knowgraph.server.service.ai.rag.SectionAwareDocumentChunker;
import com.flevin.knowgraph.server.service.association.DocumentAssociationService;
import com.flevin.knowgraph.server.service.association.DocumentAssociationClient;
import com.flevin.knowgraph.server.service.association.DocumentCandidateRecallService;
import com.flevin.knowgraph.server.service.document.DocumentService;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文档关联固定资料端到端 Fake 评估。
 *
 * <p>该测试使用真实候选召回、文档关联 Service、章节分片和 MySQL 持久化，
 * Fake 客户端只根据冻结标注生成可追溯的关系判断。指标报告由测试按固定资料
 * 重新生成，避免把一次手工观察写成评估结论。</p>
 */
@SpringBootTest(classes = KnowledgeGraphApplication.class, properties = {
        "app.upload-dir=target/test-data/document-association-evaluation-uploads",
        "test.document-association-client=evaluation"
})
@Import(DocumentAssociationFixtureEvaluationTests.FakeAssociationConfiguration.class)
class DocumentAssociationFixtureEvaluationTests {

    private static final Long SPACE_ID = TestKnowledgeSpaceFixtures.DEFAULT_SPACE_ID;
    private static final String DATASET_VERSION = "document-association-eval-v1";
    private static final String REPORT_FILE = "docs/tests/document-association-evaluation-report-v1.md";

    @Autowired
    private DocumentAssociationService associationService;

    @Autowired
    private DocumentCandidateRecallService candidateRecallService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private SourceDocumentRepository sourceDocumentRepository;

    @Autowired
    private PrdMarkdownSectionParser sectionParser;

    @Autowired
    private SectionAwareDocumentChunker documentChunker;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EvaluationAssociationClient fakeClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void clearData() {
        // 按外键依赖顺序清理评估运行、关系、证据和历史来源
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
    void evaluatesFrozenFixtureAndWritesDeterministicReport() throws IOException {
        FixtureData fixture = loadFixture();
        Map<String, SourceDocument> documents = importFixture(fixture);
        fakeClient.configure(fixture, documents);

        EvaluationResult result = evaluate(fixture, documents);
        writeReport(fixture, result);

        // 固定评估规程的第一版质量门槛
        assertThat(result.recallAt8()).isGreaterThanOrEqualTo(0.90);
        assertThat(result.nonNonePrecision()).isGreaterThanOrEqualTo(0.80);
        assertThat(result.evidenceValidity()).isEqualTo(1.0);
        assertThat(result.ungroundedSuggestionRate()).isZero();
        assertThat(result.duplicateSuggestionRate()).isZero();
        assertThat(result.selfRelationCount()).isZero();
        assertThat(result.crossSpaceRelationCount()).isZero();
        assertThat(result.relationTypeAccuracy()).isEqualTo(1.0);
        assertThat(result.directionAccuracy()).isEqualTo(1.0);
        assertThat(result.missingExpectedRelations()).isEmpty();
        assertThat(result.falsePositivePairs()).isEmpty();
        assertThat(result.contextMisses()).isEmpty();
        assertThat(result.runFailures()).isEmpty();
        assertThat(result.negativeResults().values()).doesNotContain("false_positive");
    }

    /**
     * 读取仓库内固定资料和机器可读标注。
     *
     * @return 固定资料评估输入
     * @throws IOException 资料或标注无法读取时抛出
     */
    private FixtureData loadFixture() throws IOException {
        Path fixtureRoot = findPath(
                Path.of("fixture", "document-association-v1"),
                Path.of("..", "fixture", "document-association-v1"),
                Path.of("..", "..", "fixture", "document-association-v1")
        );
        JsonNode annotations = objectMapper.readTree(
                Files.readString(fixtureRoot.resolve("annotations.json"))
        );
        List<FixtureDocument> fixtureDocuments = new ArrayList<>();
        for (JsonNode node : annotations.path("documents")) {
            fixtureDocuments.add(new FixtureDocument(
                    node.path("documentId").asText(),
                    node.path("path").asText()
            ));
        }

        List<ExpectedRelation> expectedRelations = new ArrayList<>();
        for (JsonNode node : annotations.path("expectedRelations")) {
            List<ExpectedEvidence> evidences = new ArrayList<>();
            for (JsonNode evidence : node.path("evidences")) {
                evidences.add(new ExpectedEvidence(
                        evidence.path("documentId").asText(),
                        evidence.path("quote").asText()
                ));
            }
            expectedRelations.add(new ExpectedRelation(
                    node.path("relationId").asText(),
                    node.path("sourceDocumentId").asText(),
                    node.path("targetDocumentId").asText(),
                    node.path("relationType").asText(),
                    evidences
            ));
        }

        List<NegativePair> negativePairs = new ArrayList<>();
        for (JsonNode node : annotations.path("negativePairs")) {
            negativePairs.add(new NegativePair(
                    node.path("caseId").asText(),
                    node.path("leftDocumentId").asText(),
                    node.path("rightDocumentId").asText()
            ));
        }

        List<RetrievalCase> retrievalCases = new ArrayList<>();
        for (JsonNode node : annotations.path("retrievalCases")) {
            retrievalCases.add(new RetrievalCase(
                    node.path("caseId").asText(),
                    node.path("sourceDocumentId").asText(),
                    textSet(node.path("expectedCandidateDocumentIds")),
                    textSet(node.path("hardNegativeDocumentIds"))
            ));
        }
        return new FixtureData(
                fixtureRoot,
                fixtureDocuments,
                expectedRelations,
                negativePairs,
                retrievalCases
        );
    }

    /**
     * 导入固定资料，建立 fixture ID 到真实来源资料 ID 的映射。
     *
     * @param fixture 固定资料定义
     * @return fixture ID 到来源资料的映射
     * @throws IOException 资料文件无法读取时抛出
     */
    private Map<String, SourceDocument> importFixture(FixtureData fixture) throws IOException {
        Map<String, SourceDocument> documents = new LinkedHashMap<>();
        for (FixtureDocument fixtureDocument : fixture.documents()) {
            Path file = fixture.root().resolve(fixtureDocument.path());
            byte[] content = Files.readAllBytes(file);
            MockMultipartFile upload = new MockMultipartFile(
                    "files",
                    file.getFileName().toString(),
                    "text/plain",
                    content
            );
            DocumentImportResponse response = documentService.importDocuments(
                    SPACE_ID,
                    List.of(upload)
            );
            SourceDocument document = sourceDocumentRepository.findById(
                    SPACE_ID,
                    response.results().getFirst().document().id()
            ).orElseThrow();
            documents.put(fixtureDocument.fixtureId(), document);
        }
        return documents;
    }

    /**
     * 执行真实候选召回和 Fake 关系判断，汇总固定资料指标。
     *
     * @param fixture 固定资料和标注
     * @param documents fixture ID 到来源资料映射
     * @return 可重复的评估结果
     */
    private EvaluationResult evaluate(
            FixtureData fixture,
            Map<String, SourceDocument> documents
    ) {
        Map<String, RecallResult> recallResults = new LinkedHashMap<>();
        for (RetrievalCase retrievalCase : fixture.retrievalCases()) {
            SourceDocument sourceDocument = documents.get(retrievalCase.sourceDocumentId());
            DocumentCandidateRecall recall = candidateRecallService.recall(
                    SPACE_ID,
                    sourceDocument.id()
            );
            Set<String> candidateFixtureIds = recall.candidates().stream()
                    .map(candidate -> fixtureIdByDocumentId(documents, candidate.documentId()))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            recallResults.put(
                    retrievalCase.caseId(),
                    new RecallResult(candidateFixtureIds, retrievalCase.expectedCandidateIds(), retrievalCase.hardNegativeIds())
            );
        }

        // 从每份固定资料发起一次关联运行，覆盖正反向召回和孤立文档空召回
        Map<String, DocumentAssociationRunResponse> runs = new LinkedHashMap<>();
        for (FixtureDocument fixtureDocument : fixture.documents()) {
            DocumentAssociationRunResponse run = associationService.createRun(
                    SPACE_ID,
                    documents.get(fixtureDocument.fixtureId()).id()
            );
            runs.put(fixtureDocument.fixtureId(), run);
        }

        // 从所有文档端点查询已通过校验的关系，并按服务端关系标识去重
        Map<Long, DocumentRelationResponse> persistedRelations = new LinkedHashMap<>();
        for (FixtureDocument fixtureDocument : fixture.documents()) {
            associationService.listRelations(SPACE_ID, documents.get(fixtureDocument.fixtureId()).id())
                    .forEach(relation -> persistedRelations.put(relation.id(), relation));
        }

        // 汇总召回用例的微平均 Recall@8 与 Precision@8
        int expectedCandidateCount = fixture.retrievalCases().stream()
                .mapToInt(retrievalCase -> retrievalCase.expectedCandidateIds().size())
                .sum();
        int recalledExpectedCount = recallResults.values().stream()
                .mapToInt(result -> intersection(result.candidates(), result.expectedCandidates()).size())
                .sum();
        int recalledCandidateCount = recallResults.values().stream()
                .mapToInt(result -> result.candidates().size())
                .sum();
        int hardNegativeCount = recallResults.values().stream()
                .mapToInt(result -> intersection(result.candidates(), result.hardNegatives()).size())
                .sum();

        // 对照冻结正例，定位关系漏检与服务端最终误报
        List<String> missingExpectedRelations = fixture.expectedRelations().stream()
                .filter(expected -> !containsExpectedRelation(persistedRelations.values(), expected, documents))
                .map(ExpectedRelation::relationId)
                .toList();
        List<String> falsePositivePairs = persistedRelations.values().stream()
                .filter(relation -> !matchesAnyExpectedRelation(
                        relation,
                        fixture.expectedRelations(),
                        documents
                ))
                .map(relation -> describeRelation(relation, documents))
                .toList();

        // 关系类型准确率按冻结正例计分，非 none Precision 按最终建议计分
        int correctTypeCount = fixture.expectedRelations().size() - missingExpectedRelations.size();
        int correctNonNoneCount = (int) persistedRelations.values().stream()
                .filter(relation -> matchesAnyExpectedRelation(
                        relation,
                        fixture.expectedRelations(),
                        documents
                ))
                .count();

        // 依据关系创建运行的主体文档，校验相对方向而不是假设统一为 current_to_candidate
        Map<Long, String> runSourceFixtureIdByRunId = runs.entrySet().stream()
                .collect(Collectors.toMap(
                        entry -> entry.getValue().runId(),
                        Map.Entry::getKey,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        int directedExpectedCount = (int) fixture.expectedRelations().stream()
                .filter(expected -> !Set.of("related_to", "conflicts_with").contains(expected.relationType()))
                .count();
        int correctDirectionCount = (int) fixture.expectedRelations().stream()
                .filter(expected -> !Set.of("related_to", "conflicts_with").contains(expected.relationType()))
                .filter(expected -> containsExpectedRelationWithDirection(
                        persistedRelations.values(),
                        expected,
                        documents,
                        runSourceFixtureIdByRunId
                ))
                .count();

        // 独立反查关系证据的文档、分片、章节、quote 和绝对偏移
        int validEvidenceCount = persistedRelations.values().stream()
                .flatMap(relation -> relation.evidences().stream())
                .filter(evidence -> evidenceIsValid(evidence, documents))
                .toList()
                .size();
        int evidenceCount = persistedRelations.values().stream()
                .mapToInt(relation -> relation.evidences().size())
                .sum();
        int ungroundedSuggestionCount = (int) persistedRelations.values().stream()
                .filter(relation -> relation.evidences().isEmpty()
                        || relation.evidences().stream().noneMatch(evidence -> evidenceIsValid(evidence, documents)))
                .count();

        // 规范化关系键不包含相对于运行主体的 direction，避免正反向运行重复建议
        int duplicateSuggestionCount = persistedRelations.size()
                - normalizedRelationKeys(persistedRelations.values(), documents).size();

        // 直接检查 MySQL 两端文档归属，防止响应层字段缺失掩盖跨空间关系
        int selfRelationCount = queryRelationCount(
                "SELECT COUNT(*) FROM document_relations WHERE source_document_id = target_document_id"
        );
        int crossSpaceRelationCount = queryRelationCount("""
                SELECT COUNT(*)
                FROM document_relations relation
                JOIN source_documents source_document ON source_document.id = relation.source_document_id
                JOIN source_documents target_document ON target_document.id = relation.target_document_id
                WHERE relation.space_id <> source_document.space_id
                   OR relation.space_id <> target_document.space_id
                   OR source_document.space_id <> target_document.space_id
                """);

        // 将五组冻结负例区分为召回前过滤、模型明确 none 和误报
        Map<String, String> negativeResults = fixture.negativePairs().stream()
                .collect(Collectors.toMap(
                        NegativePair::caseId,
                        pair -> fakeClient.negativeResult(pair),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));

        // 单独记录运行失败和证据未进入模型上下文的失败样例
        List<String> runFailures = runs.entrySet().stream()
                .filter(entry -> !"completed".equals(entry.getValue().status()))
                .map(entry -> entry.getKey()
                        + ": " + entry.getValue().failureStage()
                        + " - " + entry.getValue().errorMessage())
                .toList();

        return new EvaluationResult(
                expectedCandidateCount == 0 ? 1.0 : recalledExpectedCount / (double) expectedCandidateCount,
                recalledCandidateCount == 0 ? 1.0 : recalledExpectedCount / (double) recalledCandidateCount,
                fixture.expectedRelations().isEmpty() ? 1.0 : correctTypeCount / (double) fixture.expectedRelations().size(),
                directedExpectedCount == 0 ? 1.0 : correctDirectionCount / (double) directedExpectedCount,
                evidenceCount == 0 ? 1.0 : validEvidenceCount / (double) evidenceCount,
                persistedRelations.isEmpty() ? 1.0 : correctNonNoneCount / (double) persistedRelations.size(),
                ungroundedSuggestionCount / (double) Math.max(1, persistedRelations.size()),
                duplicateSuggestionCount / (double) Math.max(1, persistedRelations.size()),
                selfRelationCount,
                crossSpaceRelationCount,
                hardNegativeCount,
                missingExpectedRelations,
                falsePositivePairs,
                fakeClient.contextMisses(),
                runFailures,
                recallResults,
                negativeResults,
                persistedRelations.size()
        );
    }

    /**
     * 将评估结果写入仓库内固定报告文件。
     *
     * @param fixture 固定资料定义
     * @param result 评估结果
     * @throws IOException 报告无法写入时抛出
     */
    private void writeReport(
            FixtureData fixture,
            EvaluationResult result
    ) throws IOException {
        Path report = fixture.root().getParent().getParent().resolve(REPORT_FILE);
        StringBuilder content = new StringBuilder()
                .append("# 文档关联固定资料评估报告 v1\n\n")
                .append("- datasetVersion：").append(DATASET_VERSION).append('\n')
                .append("- Prompt：document-association-v1（Fake 基线）\n")
                .append("- Schema：document-association-v1\n")
                .append("- 候选召回：document-candidate-recall-v1，TopK=8\n")
                .append("- 关联策略：document-association-policy-v1\n")
                .append("- 运行方式：Java 21 + MySQL + 固定 Fake Association Client\n")
                .append("- 计分范围：7 条正例、5 组明确负例、7 个召回用例；未标注文档对忽略\n\n")
                .append("## 指标\n\n")
                .append("| 指标 | 结果 | 门槛 |\n| --- | ---: | ---: |\n")
                .append("| Recall@8 | ").append(format(result.recallAt8())).append(" | >= 0.90 |\n")
                .append("| Precision@8（微平均） | ").append(format(result.precisionAt8())).append(" | 记录 |\n")
                .append("| 关系类型准确率 | ").append(format(result.relationTypeAccuracy())).append(" | 记录 |\n")
                .append("| 有向关系方向准确率 | ").append(format(result.directionAccuracy())).append(" | 记录 |\n")
                .append("| 证据有效率 | ").append(format(result.evidenceValidity())).append(" | 1.00 |\n")
                .append("| 非 none Precision | ").append(format(result.nonNonePrecision())).append(" | >= 0.80 |\n")
                .append("| 无依据建议率 | ").append(format(result.ungroundedSuggestionRate())).append(" | 0.00 |\n")
                .append("| 重复建议率 | ").append(format(result.duplicateSuggestionRate())).append(" | 0.00 |\n")
                .append("| 硬负例召回数量 | ").append(result.hardNegativeCount()).append(" | 记录 |\n")
                .append("| 自关联数量 | ").append(result.selfRelationCount()).append(" | 0 |\n")
                .append("| 跨空间关系数量 | ").append(result.crossSpaceRelationCount()).append(" | 0 |\n")
                .append("| 最终非 none 建议数 | ").append(result.suggestionCount()).append(" | 记录 |\n\n")
                .append("## 失败样例\n\n");
        if (result.missingExpectedRelations().isEmpty()
                && result.falsePositivePairs().isEmpty()
                && result.contextMisses().isEmpty()
                && result.runFailures().isEmpty()) {
            content.append("本次 Fake 基线未发现正例漏检、最终关系误报、证据上下文缺失或运行失败。\n\n");
        } else {
            content.append("### 漏检关系\n\n");
            appendItems(content, result.missingExpectedRelations());
            content.append("\n### 未标注误报\n\n");
            appendItems(content, result.falsePositivePairs());
            content.append("\n### 证据上下文缺失\n\n");
            appendItems(content, result.contextMisses());
            content.append("\n### 运行失败\n\n");
            appendItems(content, result.runFailures());
            content.append("\n");
        }
        content.append("## 召回用例\n\n| caseId | 期望候选 | 实际候选 | 硬负例命中 |\n| --- | --- | --- | --- |\n");
        result.recallResults().forEach((caseId, recall) -> content
                .append("| ").append(caseId)
                .append(" | ").append(joinSorted(recall.expectedCandidates()))
                .append(" | ").append(joinSorted(recall.candidates()))
                .append(" | ").append(joinSorted(intersection(recall.candidates(), recall.hardNegatives())))
                .append(" |\n"));
        content.append("\n## 负例结果\n\n| caseId | 结果 |\n| --- | --- |\n");
        result.negativeResults().forEach((caseId, negativeResult) -> content
                .append("| ").append(caseId)
                .append(" | ").append(negativeResult)
                .append(" |\n"));
        content.append("\n## 结论与下一步\n\n")
                .append("固定 Fake 基线达到 Recall@8、证据有效率、非 none Precision、重复建议、自关联和跨空间关系质量门槛。Precision@8 微平均为 ")
                .append(format(result.precisionAt8()))
                .append("，说明无 Embedding 规则召回优先保证了覆盖，但仍有明显上下文噪声；该指标作为阶段 2 可选标签候选补充和后续混合召回的对照基线，不通过扩大 TopK 掩盖。下一开发切片进入可选标签的持久化基础、候选生成与审核后端，不在本报告中混入真实模型或前端结论。\n");
        content.append("\n## 解释与边界\n\n")
                .append("本报告是固定资料上的 Fake 基线，不代表真实模型正确率。Fake 客户端只根据冻结标注选择关系类型、方向和精确 quote；真实候选召回、有限分片上下文、候选集合校验、逐字证据反查、MySQL 幂等和关系查询均由服务端执行。Precision@8 为 7 个召回用例的微平均，负例结果区分召回前过滤、模型明确 none 和误报。真实模型接入后必须在相同版本和计分范围下重新评估，并记录模型、供应商、参数、Token、耗时和失败样例。浏览器、真实模型与生产环境均不在本报告验证范围内。\n");
        Files.writeString(report, content.toString(), StandardCharsets.UTF_8);
    }

    private boolean containsExpectedRelation(
            Iterable<?> relations,
            ExpectedRelation expected,
            Map<String, SourceDocument> documents
    ) {
        for (Object item : relations) {
            if (item instanceof DocumentRelationResponse actual
                    && matches(actual, expected, documents)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsExpectedRelationWithDirection(
            Iterable<DocumentRelationResponse> relations,
            ExpectedRelation expected,
            Map<String, SourceDocument> documents,
            Map<Long, String> runSourceFixtureIdByRunId
    ) {
        return java.util.stream.StreamSupport.stream(relations.spliterator(), false).anyMatch(actual ->
                matches(actual, expected, documents)
                        && directionMatchesRun(actual, expected, runSourceFixtureIdByRunId)
        );
    }

    private boolean directionMatchesRun(
            DocumentRelationResponse actual,
            ExpectedRelation expected,
            Map<Long, String> runSourceFixtureIdByRunId
    ) {
        String runSourceFixtureId = runSourceFixtureIdByRunId.get(actual.associationRunId());
        String expectedDirection;
        if (expected.sourceDocumentId().equals(runSourceFixtureId)) {
            expectedDirection = "current_to_candidate";
        } else if (expected.targetDocumentId().equals(runSourceFixtureId)) {
            expectedDirection = "candidate_to_current";
        } else {
            return false;
        }
        return expectedDirection.equals(actual.direction());
    }

    private boolean matchesAnyExpectedRelation(
            DocumentRelationResponse actual,
            List<ExpectedRelation> expectedRelations,
            Map<String, SourceDocument> documents
    ) {
        return expectedRelations.stream().anyMatch(expected -> matches(actual, expected, documents));
    }

    private boolean matches(
            DocumentRelationResponse actual,
            ExpectedRelation expected,
            Map<String, SourceDocument> documents
    ) {
        String actualSource = fixtureIdByDocumentId(documents, actual.sourceDocumentId());
        String actualTarget = fixtureIdByDocumentId(documents, actual.targetDocumentId());
        boolean sameDirection = expected.sourceDocumentId().equals(actualSource)
                && expected.targetDocumentId().equals(actualTarget);
        boolean reverseSymmetric = Set.of("related_to", "conflicts_with").contains(expected.relationType())
                && expected.sourceDocumentId().equals(actualTarget)
                && expected.targetDocumentId().equals(actualSource);
        return expected.relationType().equals(actual.relationType())
                && (sameDirection || reverseSymmetric);
    }

    private boolean evidenceIsValid(
            DocumentRelationResponse.Evidence evidence,
            Map<String, SourceDocument> documents
    ) {
        SourceDocument document = documents.values().stream()
                .filter(item -> item.id().equals(evidence.sourceDocumentId()))
                .findFirst()
                .orElse(null);
        if (document == null) {
            return false;
        }

        DocumentChunk chunk = documentChunker.chunk(sectionParser.parse(document.contentText())).stream()
                .filter(item -> item.chunkId().equals(evidence.chunkId()))
                .findFirst()
                .orElse(null);
        return chunk != null
                && chunk.sectionPath().equals(evidence.sectionPath())
                && chunk.contentText().contains(evidence.quote())
                && evidence.startOffset() >= 0
                && evidence.endOffset() <= document.contentText().length()
                && document.contentText().substring(evidence.startOffset(), evidence.endOffset())
                .equals(evidence.quote());
    }

    private Set<String> normalizedRelationKeys(
            Iterable<DocumentRelationResponse> relations,
            Map<String, SourceDocument> documents
    ) {
        Set<String> keys = new HashSet<>();
        for (DocumentRelationResponse relation : relations) {
            String source = fixtureIdByDocumentId(documents, relation.sourceDocumentId());
            String target = fixtureIdByDocumentId(documents, relation.targetDocumentId());
            if (Set.of("related_to", "conflicts_with").contains(relation.relationType())
                    && source.compareTo(target) > 0) {
                String swap = source;
                source = target;
                target = swap;
            }
            keys.add(source + "|" + relation.relationType() + "|" + target);
        }
        return keys;
    }

    private String describeRelation(
            DocumentRelationResponse relation,
            Map<String, SourceDocument> documents
    ) {
        return fixtureIdByDocumentId(documents, relation.sourceDocumentId())
                + " -[" + relation.relationType() + "]-> "
                + fixtureIdByDocumentId(documents, relation.targetDocumentId());
    }

    private int queryRelationCount(String sql) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class);
        return count == null ? 0 : count;
    }

    private void appendItems(
            StringBuilder content,
            List<String> items
    ) {
        if (items.isEmpty()) {
            content.append("- 无\n");
            return;
        }
        items.forEach(item -> content.append("- ").append(item).append('\n'));
    }

    private Set<String> intersection(Set<String> left, Set<String> right) {
        Set<String> result = new LinkedHashSet<>(left);
        result.retainAll(right);
        return result;
    }

    private String joinSorted(Set<String> values) {
        return values.stream().sorted().collect(Collectors.joining(", "));
    }

    private Set<String> textSet(JsonNode node) {
        Set<String> result = new LinkedHashSet<>();
        node.forEach(item -> result.add(item.asText()));
        return result;
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

    private Path findPath(Path... candidates) {
        return List.of(candidates).stream()
                .filter(Files::exists)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("找不到固定文档关联资料"));
    }

    private String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.4f", value);
    }

    @TestConfiguration
    @ConditionalOnProperty(name = "test.document-association-client", havingValue = "evaluation")
    static class FakeAssociationConfiguration {

        @Bean
        @Primary
        EvaluationAssociationClient evaluationAssociationClient() {
            return new EvaluationAssociationClient();
        }
    }

    static class EvaluationAssociationClient implements DocumentAssociationClient {

        private Map<Long, String> fixtureIdByDocumentId = Map.of();
        private List<ExpectedRelation> expectedRelations = List.of();
        private List<NegativePair> negativePairs = List.of();
        private final Map<String, String> decisionByPair = new LinkedHashMap<>();
        private final List<String> contextMisses = new ArrayList<>();

        void configure(
                FixtureData fixture,
                Map<String, SourceDocument> documents
        ) {
            fixtureIdByDocumentId = documents.entrySet().stream()
                    .collect(Collectors.toMap(
                            entry -> entry.getValue().id(),
                            Map.Entry::getKey,
                            (left, right) -> left,
                            LinkedHashMap::new
                    ));
            expectedRelations = fixture.expectedRelations();
            negativePairs = fixture.negativePairs();
            decisionByPair.clear();
            contextMisses.clear();
        }

        @Override
        public DocumentAssociationResult associate(DocumentAssociationRequest request) {
            List<DocumentAssociationEvidenceCandidate> evidences = new ArrayList<>();
            List<DocumentAssociationDecision> decisions = new ArrayList<>();
            String currentFixtureId = fixtureIdByDocumentId.get(request.currentDocument().documentId());
            for (DocumentAssociationCandidateContext candidate : request.candidates()) {
                String candidateFixtureId = fixtureIdByDocumentId.get(candidate.document().documentId());
                ExpectedRelation expected = findExpected(currentFixtureId, candidateFixtureId);
                if (expected == null) {
                    decisionByPair.put(pairKey(currentFixtureId, candidateFixtureId), "none");
                    decisions.add(new DocumentAssociationDecision(
                            candidate.document().documentId(),
                            "none",
                            "none",
                            0.5,
                            isNegative(currentFixtureId, candidateFixtureId)
                                    ? "冻结负例没有足够关系证据。"
                                    : "未在本次 Fake 基线中标注为关系。",
                            List.of(),
                            List.of()
                    ));
                    continue;
                }

                String direction = expected.sourceDocumentId().equals(currentFixtureId)
                        ? "current_to_candidate"
                        : "candidate_to_current";
                if (Set.of("related_to", "conflicts_with").contains(expected.relationType())) {
                    direction = "symmetric";
                }
                List<DocumentAssociationEvidenceCandidate> decisionEvidences = expected.evidences().stream()
                        .map(expectedEvidence -> toEvidence(
                                expected,
                                expectedEvidence,
                                request.currentDocument(),
                                candidate.document()
                        ))
                        .filter(java.util.Objects::nonNull)
                        .toList();
                if (decisionEvidences.size() != expected.evidences().size()) {
                    decisionByPair.put(pairKey(currentFixtureId, candidateFixtureId), "context_missing");
                    decisions.add(new DocumentAssociationDecision(
                            candidate.document().documentId(),
                            "none",
                            "none",
                            0.5,
                            "冻结正例证据未进入本次有限分片上下文。",
                            List.of(),
                            List.of()
                    ));
                    continue;
                }
                evidences.addAll(decisionEvidences);
                List<String> evidenceIds = decisionEvidences.stream()
                        .map(DocumentAssociationEvidenceCandidate::evidenceId)
                        .toList();
                decisionByPair.put(pairKey(currentFixtureId, candidateFixtureId), expected.relationType());
                decisions.add(new DocumentAssociationDecision(
                        candidate.document().documentId(),
                        expected.relationType(),
                        direction,
                        0.95,
                        "固定标注关系的 Fake 基线判断。",
                        List.of(),
                        evidenceIds
                ));
            }
            return new DocumentAssociationResult(evidences, decisions);
        }

        private DocumentAssociationEvidenceCandidate toEvidence(
                ExpectedRelation relation,
                ExpectedEvidence expectedEvidence,
                DocumentAssociationDocumentContext currentDocument,
                DocumentAssociationDocumentContext candidateDocument
        ) {
            DocumentAssociationDocumentContext evidenceDocument = fixtureIdByDocumentId
                    .get(currentDocument.documentId())
                    .equals(expectedEvidence.documentId())
                    ? currentDocument
                    : candidateDocument;
            DocumentChunk chunk = evidenceDocument.chunks().stream()
                    .filter(item -> item.contentText().contains(expectedEvidence.quote()))
                    .findFirst()
                    .orElse(null);
            if (chunk == null) {
                contextMisses.add(relation.relationId() + ": " + expectedEvidence.documentId());
                return null;
            }
            return new DocumentAssociationEvidenceCandidate(
                    relation.relationId() + "-" + expectedEvidence.documentId(),
                    evidenceDocument.documentId(),
                    chunk.chunkId(),
                    chunk.sectionPath(),
                    expectedEvidence.quote()
            );
        }

        private ExpectedRelation findExpected(String current, String candidate) {
            return expectedRelations.stream()
                    .filter(expected ->
                            (expected.sourceDocumentId().equals(current)
                                    && expected.targetDocumentId().equals(candidate))
                                    || (expected.sourceDocumentId().equals(candidate)
                                    && expected.targetDocumentId().equals(current)))
                    .findFirst()
                    .orElse(null);
        }

        private boolean isNegative(String left, String right) {
            return negativePairs.stream().anyMatch(pair ->
                    (pair.leftDocumentId().equals(left) && pair.rightDocumentId().equals(right))
                            || (pair.leftDocumentId().equals(right) && pair.rightDocumentId().equals(left))
            );
        }

        private String negativeResult(NegativePair pair) {
            String decision = decisionByPair.get(pairKey(pair.leftDocumentId(), pair.rightDocumentId()));
            if (decision == null) {
                return "filtered_before_model";
            }
            return "none".equals(decision) ? "none" : "false_positive";
        }

        private List<String> contextMisses() {
            return List.copyOf(contextMisses);
        }

        private String pairKey(String left, String right) {
            return left.compareTo(right) <= 0
                    ? left + "|" + right
                    : right + "|" + left;
        }
    }

    private record FixtureData(
            Path root,
            List<FixtureDocument> documents,
            List<ExpectedRelation> expectedRelations,
            List<NegativePair> negativePairs,
            List<RetrievalCase> retrievalCases
    ) {
    }

    private record FixtureDocument(String fixtureId, String path) {
    }

    private record ExpectedRelation(
            String relationId,
            String sourceDocumentId,
            String targetDocumentId,
            String relationType,
            List<ExpectedEvidence> evidences
    ) {
    }

    private record ExpectedEvidence(String documentId, String quote) {
    }

    private record NegativePair(String caseId, String leftDocumentId, String rightDocumentId) {
    }

    private record RetrievalCase(
            String caseId,
            String sourceDocumentId,
            Set<String> expectedCandidateIds,
            Set<String> hardNegativeIds
    ) {
    }

    private record RecallResult(
            Set<String> candidates,
            Set<String> expectedCandidates,
            Set<String> hardNegatives
    ) {
    }

    private record EvaluationResult(
            double recallAt8,
            double precisionAt8,
            double relationTypeAccuracy,
            double directionAccuracy,
            double evidenceValidity,
            double nonNonePrecision,
            double ungroundedSuggestionRate,
            double duplicateSuggestionRate,
            int selfRelationCount,
            int crossSpaceRelationCount,
            int hardNegativeCount,
            List<String> missingExpectedRelations,
            List<String> falsePositivePairs,
            List<String> contextMisses,
            List<String> runFailures,
            Map<String, RecallResult> recallResults,
            Map<String, String> negativeResults,
            int suggestionCount
    ) {
    }
}

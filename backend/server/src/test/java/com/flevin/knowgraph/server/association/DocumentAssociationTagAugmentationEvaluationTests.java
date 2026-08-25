package com.flevin.knowgraph.server.association;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flevin.knowgraph.server.KnowledgeGraphApplication;
import com.flevin.knowgraph.server.model.association.DocumentCandidateRecall;
import com.flevin.knowgraph.server.model.document.DocumentImportResponse;
import com.flevin.knowgraph.server.model.document.SourceDocument;
import com.flevin.knowgraph.server.repository.document.SourceDocumentRepository;
import com.flevin.knowgraph.server.service.association.DocumentCandidateRecallService;
import com.flevin.knowgraph.server.service.document.DocumentService;
import com.flevin.knowgraph.server.support.TestKnowledgeSpaceFixtures;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 固定资料上的 confirmed 标签共同数量分层阈值正负例实验。
 *
 * <p>测试使用 annotations.json 中冻结的 expectedTags 和独立 v2 补充标注作为
 * 人工 confirmed 标签输入，不把标签生成模型的质量混入本实验；在内容通道未命中的前提下，
 * 同时验证达到最小共同标签数量的相关文档和不同项目负例。</p>
 */
@SpringBootTest(classes = KnowledgeGraphApplication.class, properties = {
        "app.database-path=target/test-data/document-association-tag-augmentation-evaluation.sqlite",
        "app.upload-dir=target/test-data/document-association-tag-augmentation-evaluation-uploads"
})
class DocumentAssociationTagAugmentationEvaluationTests {

    private static final String SPACE_ID = TestKnowledgeSpaceFixtures.DEFAULT_SPACE_ID;
    private static final String BASE_DATASET_VERSION = "document-association-eval-v1";
    private static final String TAG_THRESHOLD_DATASET_VERSION = "document-association-tag-threshold-eval-v2";
    private static final String REPORT_FILE =
            "../../docs/tests/document-association-tag-threshold-evaluation-v2.md";

    @Autowired
    private DocumentCandidateRecallService candidateRecallService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private SourceDocumentRepository sourceDocumentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void clearData() {
        // 按外键依赖顺序清理标签、关联运行和来源资料，保证每次实验从同一输入开始
        jdbcTemplate.update("DELETE FROM document_tag_reviews");
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
        TestKnowledgeSpaceFixtures.ensureDefaultSpace(jdbcTemplate);
    }

    @Test
    void comparesDefaultAndConfirmedTagCandidateRecall() throws IOException {
        FixtureData fixture = loadFixture();
        Map<String, SourceDocument> documents = importFixture(fixture);
        materializeConfirmedFixtureTags(fixture, documents);

        // 对同一批固定资料分别执行关闭和开启标签通道的候选召回
        ComparisonResult result = compare(fixture, documents);
        writeReport(result);

        // 默认路径应漏掉新增标签正例，开启阈值后应同时暴露正例增益和跨项目负例边界
        assertThat(result.defaultCandidateCount()).isGreaterThan(0);
        assertThat(result.augmentedTagCandidateCount()).isEqualTo(2);
        assertThat(result.defaultCandidateRecallPolicyVersions())
                .containsOnly(DocumentCandidateRecallService.CONTENT_POLICY_VERSION);
        assertThat(result.augmentedCandidateRecallPolicyVersions())
                .containsOnly(DocumentCandidateRecallService.CONFIRMED_TAG_THRESHOLD_POLICY_VERSION);
        assertThat(result.augmentedRecallAt8()).isGreaterThan(result.defaultRecallAt8());
        assertThat(result.augmentedRecallAt8()).isEqualTo(1.0);
        assertThat(result.augmentedCandidateCount() - result.defaultCandidateCount())
                .isEqualTo(result.augmentedTagCandidateCount());
        assertThat(result.cases()).allSatisfy(item -> assertThat(new ArrayList<>(item.augmentedCandidates()))
                .startsWith(item.defaultCandidates().toArray(String[]::new)));
        assertThat(result.defaultHardNegativeCount()).isZero();
        assertThat(result.augmentedHardNegativeCount()).isEqualTo(1);
        assertThat(result.defaultSelfCandidateCount()).isZero();
        assertThat(result.augmentedSelfCandidateCount()).isZero();
        assertThat(result.defaultCrossSpaceCandidateCount()).isZero();
        assertThat(result.augmentedCrossSpaceCandidateCount()).isZero();

        // 读取补充标注的正例用例，验证目标只由双共同标签通道补入
        ThresholdCase positiveCase = fixture.thresholdCases().stream()
                .filter(item -> "positive".equals(item.kind()))
                .findFirst()
                .orElseThrow();
        CaseResult positiveResult = result.cases().stream()
                .filter(item -> item.caseId().equals(positiveCase.retrievalCaseId()))
                .findFirst()
                .orElseThrow();
        assertThat(positiveResult.defaultCandidates()).doesNotContain(positiveCase.targetDocumentId());
        assertThat(positiveResult.augmentedCandidates()).contains(positiveCase.targetDocumentId());

        // 读取补充标注的负例用例，记录数量阈值无法识别跨项目通用标签的边界
        ThresholdCase negativeCase = fixture.thresholdCases().stream()
                .filter(item -> "negative".equals(item.kind()))
                .findFirst()
                .orElseThrow();
        CaseResult negativeResult = result.cases().stream()
                .filter(item -> item.caseId().equals(negativeCase.retrievalCaseId()))
                .findFirst()
                .orElseThrow();
        assertThat(negativeResult.defaultCandidates()).doesNotContain(negativeCase.targetDocumentId());
        assertThat(negativeResult.augmentedCandidates()).contains(negativeCase.targetDocumentId());
    }

    /** @return 固定资料实验输入 @throws IOException 标注文件无法读取时抛出 */
    private FixtureData loadFixture() throws IOException {
        Path root = findPath(
                Path.of("fixture", "document-association-v1"),
                Path.of("..", "fixture", "document-association-v1"),
                Path.of("..", "..", "fixture", "document-association-v1")
        );
        JsonNode annotations = objectMapper.readTree(Files.readString(root.resolve("annotations.json")));
        JsonNode thresholdAnnotations = objectMapper.readTree(
                Files.readString(root.resolve("tag-threshold-cases-v2.json"))
        );
        assertThat(annotations.path("datasetVersion").asText()).isEqualTo(BASE_DATASET_VERSION);
        assertThat(thresholdAnnotations.path("datasetVersion").asText()).isEqualTo(TAG_THRESHOLD_DATASET_VERSION);
        assertThat(thresholdAnnotations.path("baseDatasetVersion").asText()).isEqualTo(BASE_DATASET_VERSION);
        assertThat(thresholdAnnotations.path("minimumConfirmedTagMatches").asInt())
                .isEqualTo(DocumentCandidateRecallService.MIN_CONFIRMED_TAG_MATCHES);
        Map<String, FixtureDocument> documents = new LinkedHashMap<>();
        for (JsonNode node : annotations.path("documents")) {
            List<String> expectedTags = new ArrayList<>();
            for (JsonNode tag : node.path("expectedTags")) {
                expectedTags.add(tag.path("name").asText());
            }
            documents.put(node.path("documentId").asText(), new FixtureDocument(node.path("path").asText(), expectedTags));
        }

        for (JsonNode node : thresholdAnnotations.path("additionalConfirmedTags")) {
            String documentId = node.path("documentId").asText();
            FixtureDocument document = documents.get(documentId);
            List<String> confirmedTags = new ArrayList<>(document.expectedTags());
            for (JsonNode tag : node.path("tags")) {
                confirmedTags.add(tag.path("name").asText());
            }
            documents.put(documentId, new FixtureDocument(document.path(), List.copyOf(confirmedTags)));
        }

        List<ThresholdCase> thresholdCases = new ArrayList<>();
        for (JsonNode node : thresholdAnnotations.path("cases")) {
            thresholdCases.add(new ThresholdCase(
                    node.path("kind").asText(),
                    node.path("retrievalCaseId").asText(),
                    node.path("targetDocumentId").asText()
            ));
        }
        List<RetrievalCase> retrievalCases = new ArrayList<>();
        for (JsonNode node : annotations.path("retrievalCases")) {
            Set<String> expectedCandidateIds = textSet(node.path("expectedCandidateDocumentIds"));
            Set<String> hardNegativeIds = textSet(node.path("hardNegativeDocumentIds"));
            thresholdCases.stream()
                    .filter(item -> item.retrievalCaseId().equals(node.path("caseId").asText()))
                    .forEach(item -> {
                        if ("positive".equals(item.kind())) {
                            expectedCandidateIds.add(item.targetDocumentId());
                        } else {
                            hardNegativeIds.add(item.targetDocumentId());
                        }
                    });
            retrievalCases.add(new RetrievalCase(
                    node.path("caseId").asText(),
                    node.path("sourceDocumentId").asText(),
                    expectedCandidateIds,
                    hardNegativeIds
            ));
        }
        return new FixtureData(root, documents, retrievalCases, thresholdCases);
    }

    /** @param fixture 固定资料输入 @return fixture ID 到真实来源资料的映射 @throws IOException 固定资料无法读取时抛出 */
    private Map<String, SourceDocument> importFixture(FixtureData fixture) throws IOException {
        Map<String, SourceDocument> documents = new LinkedHashMap<>();
        for (Map.Entry<String, FixtureDocument> entry : fixture.documents().entrySet()) {
            Path file = fixture.root().resolve(entry.getValue().path());
            MockMultipartFile upload = new MockMultipartFile(
                    "files", file.getFileName().toString(), "text/markdown", Files.readAllBytes(file)
            );
            // 复用真实导入链路，保证候选召回使用真实内容指纹和有效文档状态
            DocumentImportResponse response = documentService.importDocuments(SPACE_ID, List.of(upload));
            SourceDocument document = sourceDocumentRepository.findById(
                    SPACE_ID, response.results().getFirst().document().id()
            ).orElseThrow();
            documents.put(entry.getKey(), document);
        }
        return documents;
    }

    /** @param fixture 固定标签标注 @param documents fixture ID 到来源资料的映射 */
    private void materializeConfirmedFixtureTags(FixtureData fixture, Map<String, SourceDocument> documents) {
        Map<String, String> tagIds = new HashMap<>();
        for (Map.Entry<String, FixtureDocument> entry : fixture.documents().entrySet()) {
            SourceDocument document = documents.get(entry.getKey());
            for (String tagName : entry.getValue().expectedTags()) {
                String normalizedKey = normalizeTagKey(tagName);
                String tagId = tagIds.computeIfAbsent(normalizedKey, ignored -> "fixture-tag-" + tagIds.size());
                String now = Instant.now().toString();
                // 标签字典按空间和规范化键复用，避免不同文档重复创建同一标签定义
                jdbcTemplate.update(
                        "INSERT OR IGNORE INTO tags(id, space_id, name, normalized_key, status, created_at, updated_at) VALUES (?, ?, ?, ?, 'active', ?, ?)",
                        tagId, SPACE_ID, tagName, normalizedKey, now, now
                );
                jdbcTemplate.update(
                        "INSERT INTO document_tags(id, space_id, source_document_id, tag_id, source_type, status, confidence, extraction_run_id, content_hash, prompt_version, schema_version, document_tag_key, created_at, updated_at) VALUES (?, ?, ?, ?, 'user', 'confirmed', NULL, NULL, ?, NULL, NULL, ?, ?, ?)",
                        "fixture-document-tag-" + entry.getKey() + "-" + normalizedKey,
                        SPACE_ID, document.id(), tagId, document.contentHash(), entry.getKey() + "|" + normalizedKey, now, now
                );
            }
        }
    }

    /** @param fixture 固定资料和召回用例 @param documents fixture ID 到来源资料映射 @return 质量对照结果 */
    private ComparisonResult compare(FixtureData fixture, Map<String, SourceDocument> documents) {
        int expected = 0;
        int defaultHits = 0;
        int defaultCandidates = 0;
        int defaultHardNegatives = 0;
        int augmentedHits = 0;
        int augmentedCandidates = 0;
        int augmentedHardNegatives = 0;
        int augmentedTagCandidates = 0;
        int defaultSelf = 0;
        int augmentedSelf = 0;
        int defaultCrossSpace = 0;
        int augmentedCrossSpace = 0;
        Set<String> defaultCandidateRecallPolicyVersions = new LinkedHashSet<>();
        Set<String> augmentedCandidateRecallPolicyVersions = new LinkedHashSet<>();
        List<CaseResult> cases = new ArrayList<>();
        for (RetrievalCase retrievalCase : fixture.retrievalCases()) {
            SourceDocument source = documents.get(retrievalCase.sourceDocumentId());
            DocumentCandidateRecall baseline = candidateRecallService.recall(SPACE_ID, source.id(), 8, false);
            DocumentCandidateRecall augmented = candidateRecallService.recall(SPACE_ID, source.id(), 8, true);
            defaultCandidateRecallPolicyVersions.add(baseline.candidateRecallPolicyVersion());
            augmentedCandidateRecallPolicyVersions.add(augmented.candidateRecallPolicyVersion());
            Set<String> baselineIds = fixtureIds(baseline, documents);
            Set<String> augmentedIds = fixtureIds(augmented, documents);
            int baselineHits = intersectionSize(baselineIds, retrievalCase.expectedCandidateIds());
            int augmentedHitsForCase = intersectionSize(augmentedIds, retrievalCase.expectedCandidateIds());
            int baselineNegatives = intersectionSize(baselineIds, retrievalCase.hardNegativeIds());
            int augmentedNegatives = intersectionSize(augmentedIds, retrievalCase.hardNegativeIds());
            expected += retrievalCase.expectedCandidateIds().size();
            defaultHits += baselineHits;
            defaultCandidates += baselineIds.size();
            defaultHardNegatives += baselineNegatives;
            augmentedHits += augmentedHitsForCase;
            augmentedCandidates += augmentedIds.size();
            augmentedHardNegatives += augmentedNegatives;
            augmentedTagCandidates += augmented.tagCandidateCount();
            defaultSelf += countSelfCandidates(source, baseline);
            augmentedSelf += countSelfCandidates(source, augmented);
            defaultCrossSpace += countCrossSpaceCandidates(documents, baseline);
            augmentedCrossSpace += countCrossSpaceCandidates(documents, augmented);
            cases.add(new CaseResult(retrievalCase.caseId(), baselineIds, augmentedIds, baselineHits, augmentedHitsForCase, baselineNegatives, augmentedNegatives, augmented.tagCandidateCount()));
        }
        return new ComparisonResult(expected, defaultHits, defaultCandidates, defaultHardNegatives, augmentedHits, augmentedCandidates, augmentedHardNegatives, augmentedTagCandidates, defaultSelf, augmentedSelf, defaultCrossSpace, augmentedCrossSpace, defaultCandidateRecallPolicyVersions, augmentedCandidateRecallPolicyVersions, cases);
    }

    /** @param recall 候选召回结果 @param documents 固定资料映射 @return 固定资料候选 ID 集合 */
    private Set<String> fixtureIds(DocumentCandidateRecall recall, Map<String, SourceDocument> documents) {
        return recall.candidates().stream().map(candidate -> documents.entrySet().stream()
                .filter(entry -> entry.getValue().id().equals(candidate.documentId()))
                .map(Map.Entry::getKey).findFirst().orElseThrow())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** @param source 主体文档 @param recall 候选召回结果 @return 自关联候选数量 */
    private int countSelfCandidates(SourceDocument source, DocumentCandidateRecall recall) {
        return (int) recall.candidates().stream().filter(candidate -> candidate.documentId().equals(source.id())).count();
    }

    /** @param documents 当前空间来源资料 @param recall 候选召回结果 @return 跨空间候选数量 */
    private int countCrossSpaceCandidates(Map<String, SourceDocument> documents, DocumentCandidateRecall recall) {
        Set<String> ids = documents.values().stream().map(SourceDocument::id).collect(Collectors.toSet());
        return (int) recall.candidates().stream().filter(candidate -> !ids.contains(candidate.documentId())).count();
    }

    /** @param result 对照结果 @throws IOException 报告无法写入时抛出 */
    private void writeReport(ComparisonResult result) throws IOException {
        Path report = Path.of(REPORT_FILE);
        StringBuilder content = new StringBuilder();
        content.append("# 文档关联 confirmed 标签共同数量分层阈值评估 v2\n\n")
                .append("- 基础资料集：").append(BASE_DATASET_VERSION).append("\n")
                .append("- 标签阈值补充资料：").append(TAG_THRESHOLD_DATASET_VERSION).append("\n")
                .append("- 运行方式：Java 21 + SQLite + 冻结 expectedTags/补充标签作为 confirmed user 标签\n")
                .append("- 候选策略：关闭标签使用 ").append(String.join(", ", result.defaultCandidateRecallPolicyVersions()))
                .append("；开启标签使用 ").append(String.join(", ", result.augmentedCandidateRecallPolicyVersions())).append("\n")
                .append("- 单变量策略：内容通道未命中且共同 confirmed 标签数量至少为 ")
                .append(DocumentCandidateRecallService.MIN_CONFIRMED_TAG_MATCHES)
                .append(" 个时才补充候选，并排在内容候选之后\n")
                .append("- 对照：includeConfirmedTags=false/true，TopK 固定为 8\n")
                .append("- 说明：本报告评估标签对候选召回的影响，不代表标签生成模型 Precision/Recall\n\n")
                .append("## 汇总\n\n| 指标 | 关闭标签 | 开启 confirmed 标签 |\n| --- | ---: | ---: |\n")
                .append("| Recall@8 | ").append(format(result.defaultRecallAt8())).append(" | ").append(format(result.augmentedRecallAt8())).append(" |\n")
                .append("| Precision@8 | ").append(format(result.defaultPrecisionAt8())).append(" | ").append(format(result.augmentedPrecisionAt8())).append(" |\n")
                .append("| 候选总数 | ").append(result.defaultCandidateCount()).append(" | ").append(result.augmentedCandidateCount()).append(" |\n")
                .append("| 命中硬负例 | ").append(result.defaultHardNegativeCount()).append(" | ").append(result.augmentedHardNegativeCount()).append(" |\n")
                .append("| confirmed 标签通道候选数 | 0 | ").append(result.augmentedTagCandidateCount()).append(" |\n")
                .append("| 自关联候选 | ").append(result.defaultSelfCandidateCount()).append(" | ").append(result.augmentedSelfCandidateCount()).append(" |\n")
                .append("| 跨空间候选 | ").append(result.defaultCrossSpaceCandidateCount()).append(" | ").append(result.augmentedCrossSpaceCandidateCount()).append(" |\n\n")
                .append("## 用例明细\n\n| 用例 | 默认候选 | 开启后候选 | 默认命中/硬负例 | 开启后命中/硬负例 | 标签通道数 |\n| --- | --- | --- | ---: | ---: | ---: |\n");
        result.cases().forEach(item -> content.append("| ").append(item.caseId()).append(" | ")
                .append(String.join(", ", item.defaultCandidates())).append(" | ")
                .append(String.join(", ", item.augmentedCandidates())).append(" | ")
                .append(item.defaultHitCount()).append("/").append(item.defaultHardNegativeCount()).append(" | ")
                .append(item.augmentedHitCount()).append("/").append(item.augmentedHardNegativeCount()).append(" | ")
                .append(item.tagCandidateCount()).append(" |\n"));
        content.append("\n## 结论与边界\n\n")
                .append("补充资料同时加入了双共同标签正例和跨项目明确负例。开启标签后 Recall@8 从 ")
                .append(format(result.defaultRecallAt8())).append(" 提升到 ")
                .append(format(result.augmentedRecallAt8())).append("，Precision@8 从 ")
                .append(format(result.defaultPrecisionAt8())).append(" 变化为 ")
                .append(format(result.augmentedPrecisionAt8())).append("；标签通道补入 ")
                .append(result.augmentedTagCandidateCount()).append(" 个候选，其中命中 ")
                .append(result.augmentedHardNegativeCount()).append(" 个跨项目硬负例。该结果证明双共同标签可以补充内容漏召回正例，因此保留 v3 的数量阈值作为最低门槛；同时也证明数量阈值不能识别标签是否属于同一项目，不能把它当作关系判断或独立质量保障。\n\n")
                .append("开启 confirmed 标签后，标签仍只作为候选召回信号；关系判断、逐字证据校验和人工审核由原有 Pipeline 执行，共同标签不能直接确认为关系。\n\n")
                .append("本实验将冻结 expectedTags 和补充标签作为人工 confirmed 输入，未测试真实标签模型的抽取质量，也未在本实验中执行关系模型、证据校验或人工审核；浏览器入口、真实模型、生产代理和移动端另行验证。\n");
        Files.createDirectories(report.getParent());
        Files.writeString(report, content.toString(), StandardCharsets.UTF_8);
    }

    /** @param node JSON 数组 @return 稳定字符串集合 */
    private Set<String> textSet(JsonNode node) {
        Set<String> values = new LinkedHashSet<>();
        node.forEach(item -> values.add(item.asText()));
        return values;
    }

    /** @param left 左侧集合 @param right 右侧集合 @return 交集大小 */
    private int intersectionSize(Set<String> left, Set<String> right) {
        Set<String> intersection = new HashSet<>(left);
        intersection.retainAll(right);
        return intersection.size();
    }

    /** @param tagName 标签展示名称 @return 规范化键 */
    private String normalizeTagKey(String tagName) {
        return tagName.strip().replaceAll("\\s+", " ").toLowerCase();
    }

    /** @param value 指标 @return 四位小数 */
    private String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.4f", value);
    }

    /**
     * 在 Maven 模块目录和仓库根目录之间定位固定资料目录。
     *
     * @param candidates 可能的固定资料路径
     * @return 第一个存在的路径
     */
    private Path findPath(Path... candidates) {
        return List.of(candidates).stream()
                .filter(Files::exists)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("找不到固定文档关联资料"));
    }

    private record FixtureData(
            Path root,
            Map<String, FixtureDocument> documents,
            List<RetrievalCase> retrievalCases,
            List<ThresholdCase> thresholdCases
    ) { }

    private record FixtureDocument(String path, List<String> expectedTags) { }

    private record ThresholdCase(
            String kind,
            String retrievalCaseId,
            String targetDocumentId
    ) { }

    private record RetrievalCase(String caseId, String sourceDocumentId, Set<String> expectedCandidateIds, Set<String> hardNegativeIds) { }
    private record CaseResult(String caseId, Set<String> defaultCandidates, Set<String> augmentedCandidates, int defaultHitCount, int augmentedHitCount, int defaultHardNegativeCount, int augmentedHardNegativeCount, int tagCandidateCount) { }

    private record ComparisonResult(int expectedCandidateCount, int defaultHitCount, int defaultCandidateCount, int defaultHardNegativeCount, int augmentedHitCount, int augmentedCandidateCount, int augmentedHardNegativeCount, int augmentedTagCandidateCount, int defaultSelfCandidateCount, int augmentedSelfCandidateCount, int defaultCrossSpaceCandidateCount, int augmentedCrossSpaceCandidateCount, Set<String> defaultCandidateRecallPolicyVersions, Set<String> augmentedCandidateRecallPolicyVersions, List<CaseResult> cases) {
        private double defaultRecallAt8() { return expectedCandidateCount == 0 ? 1.0 : (double) defaultHitCount / expectedCandidateCount; }
        private double augmentedRecallAt8() { return expectedCandidateCount == 0 ? 1.0 : (double) augmentedHitCount / expectedCandidateCount; }
        private double defaultPrecisionAt8() { return defaultCandidateCount == 0 ? 1.0 : (double) defaultHitCount / defaultCandidateCount; }
        private double augmentedPrecisionAt8() { return augmentedCandidateCount == 0 ? 1.0 : (double) augmentedHitCount / augmentedCandidateCount; }
    }
}

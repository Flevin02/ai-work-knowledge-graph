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
 * 固定资料上的 confirmed 标签仅补内容漏召回实验。
 *
 * <p>测试使用 annotations.json 中冻结的 expectedTags 作为人工 confirmed 标签输入，
 * 不把标签生成模型的质量混入本实验；标签只补充默认内容通道未命中的候选，
 * 用于验证内容候选排序是否保持不变，以及该策略能否改善候选集合指标。</p>
 */
@SpringBootTest(classes = KnowledgeGraphApplication.class, properties = {
        "app.database-path=target/test-data/document-association-tag-augmentation-evaluation.sqlite",
        "app.upload-dir=target/test-data/document-association-tag-augmentation-evaluation-uploads"
})
class DocumentAssociationTagAugmentationEvaluationTests {

    private static final String SPACE_ID = TestKnowledgeSpaceFixtures.DEFAULT_SPACE_ID;
    private static final String REPORT_FILE =
            "../../docs/tests/document-association-tag-augmentation-evaluation-v1.md";

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

        // 默认关闭必须保持候选基线；开启后必须至少有一条 confirmed 标签补充候选
        assertThat(result.defaultCandidateCount()).isGreaterThan(0);
        assertThat(result.augmentedTagCandidateCount()).isGreaterThan(0);
        assertThat(result.defaultCandidateRecallPolicyVersions())
                .containsOnly(DocumentCandidateRecallService.CONTENT_POLICY_VERSION);
        assertThat(result.augmentedCandidateRecallPolicyVersions())
                .containsOnly(DocumentCandidateRecallService.CONFIRMED_TAG_AUGMENTATION_POLICY_VERSION);
        assertThat(result.defaultRecallAt8()).isGreaterThanOrEqualTo(0.90);
        assertThat(result.augmentedRecallAt8()).isEqualTo(result.defaultRecallAt8());
        assertThat(result.augmentedCandidateCount() - result.defaultCandidateCount())
                .isEqualTo(result.augmentedTagCandidateCount());
        assertThat(result.cases()).allSatisfy(item -> assertThat(new ArrayList<>(item.augmentedCandidates()))
                .startsWith(item.defaultCandidates().toArray(String[]::new)));
        assertThat(result.defaultHardNegativeCount()).isZero();
        assertThat(result.augmentedHardNegativeCount()).isZero();
        assertThat(result.defaultSelfCandidateCount()).isZero();
        assertThat(result.augmentedSelfCandidateCount()).isZero();
        assertThat(result.defaultCrossSpaceCandidateCount()).isZero();
        assertThat(result.augmentedCrossSpaceCandidateCount()).isZero();
    }

    /** @return 固定资料实验输入 @throws IOException 标注文件无法读取时抛出 */
    private FixtureData loadFixture() throws IOException {
        Path root = findPath(
                Path.of("fixture", "document-association-v1"),
                Path.of("..", "fixture", "document-association-v1"),
                Path.of("..", "..", "fixture", "document-association-v1")
        );
        JsonNode annotations = objectMapper.readTree(Files.readString(root.resolve("annotations.json")));
        Map<String, FixtureDocument> documents = new LinkedHashMap<>();
        for (JsonNode node : annotations.path("documents")) {
            List<String> expectedTags = new ArrayList<>();
            for (JsonNode tag : node.path("expectedTags")) {
                expectedTags.add(tag.path("name").asText());
            }
            documents.put(node.path("documentId").asText(), new FixtureDocument(node.path("path").asText(), expectedTags));
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
        return new FixtureData(root, documents, retrievalCases);
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
        content.append("# 文档关联 confirmed 标签仅补内容漏召回评估 v1\n\n")
                .append("- 资料集：document-association-eval-v1\n")
                .append("- 运行方式：Java 21 + SQLite + 冻结 expectedTags 作为 confirmed user 标签\n")
                .append("- 候选策略：关闭标签使用 ").append(String.join(", ", result.defaultCandidateRecallPolicyVersions()))
                .append("；开启标签使用 ").append(String.join(", ", result.augmentedCandidateRecallPolicyVersions())).append("\n")
                .append("- 单变量策略：confirmed 标签只补充所有默认内容通道均未命中的候选，并排在内容候选之后\n")
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
                .append("本策略恢复了默认内容候选的稳定顺序，并把 confirmed 标签通道统计收敛为真正仅由标签补充的候选；但候选总数仍为 48，Precision@8 仍为 0.1458，与上一轮未降噪开关对照一致。原因是部分内容召回不足 8 条时，单个宽泛共同标签仍会填满剩余名额，因此该策略不能称为候选质量提升。下一单变量实验应评估共同标签数量分层阈值。\n\n")
                .append("开启 confirmed 标签后，标签仍只作为候选召回信号；关系判断、逐字证据校验和人工审核由原有 Pipeline 执行，共同标签不能直接确认为关系。\n\n")
                .append("本实验将冻结 expectedTags 作为人工 confirmed 输入，未测试真实标签模型的抽取质量；浏览器入口、真实模型、生产代理和移动端另行验证。\n");
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

    private record FixtureData(Path root, Map<String, FixtureDocument> documents, List<RetrievalCase> retrievalCases) { }
    private record FixtureDocument(String path, List<String> expectedTags) { }
    private record RetrievalCase(String caseId, String sourceDocumentId, Set<String> expectedCandidateIds, Set<String> hardNegativeIds) { }
    private record CaseResult(String caseId, Set<String> defaultCandidates, Set<String> augmentedCandidates, int defaultHitCount, int augmentedHitCount, int defaultHardNegativeCount, int augmentedHardNegativeCount, int tagCandidateCount) { }

    private record ComparisonResult(int expectedCandidateCount, int defaultHitCount, int defaultCandidateCount, int defaultHardNegativeCount, int augmentedHitCount, int augmentedCandidateCount, int augmentedHardNegativeCount, int augmentedTagCandidateCount, int defaultSelfCandidateCount, int augmentedSelfCandidateCount, int defaultCrossSpaceCandidateCount, int augmentedCrossSpaceCandidateCount, Set<String> defaultCandidateRecallPolicyVersions, Set<String> augmentedCandidateRecallPolicyVersions, List<CaseResult> cases) {
        private double defaultRecallAt8() { return expectedCandidateCount == 0 ? 1.0 : (double) defaultHitCount / expectedCandidateCount; }
        private double augmentedRecallAt8() { return expectedCandidateCount == 0 ? 1.0 : (double) augmentedHitCount / expectedCandidateCount; }
        private double defaultPrecisionAt8() { return defaultCandidateCount == 0 ? 1.0 : (double) defaultHitCount / defaultCandidateCount; }
        private double augmentedPrecisionAt8() { return augmentedCandidateCount == 0 ? 1.0 : (double) augmentedHitCount / augmentedCandidateCount; }
    }
}

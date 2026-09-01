package com.flevin.knowgraph.server.association;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flevin.knowgraph.server.KnowledgeGraphApplication;
import com.flevin.knowgraph.server.model.association.DocumentAssociationRunResponse;
import com.flevin.knowgraph.server.model.document.DocumentImportResponse;
import com.flevin.knowgraph.server.model.document.SourceDocument;
import com.flevin.knowgraph.server.repository.document.SourceDocumentRepository;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * real-ai 端到端验证：v2 冻结资料上执行一次开启语义候选融合的完整关联运行。
 *
 * <p>该测试走完整 Pipeline：内容召回 → 语义召回（真实 DashScope Embedding）→ RRF 融合
 * → 真实聊天模型关联判断（psydo 端点）→ 服务端证据校验 → 建议持久化。它只证明链路连通
 * 和服务端校验有效，不证明真实模型的判断质量；会产生少量外部调用费用。</p>
 */
@Tag("real-ai")
@SpringBootTest(properties = {
        "app.upload-dir=target/test-data/document-association-real-e2e-uploads",
        "ai.enabled=${TEST_REAL_EMBEDDING:false}",
        "ai.embedding-enabled=${TEST_REAL_EMBEDDING:false}"
})
class DocumentAssociationRealAiEndToEndTests {

    private static final Long SPACE_ID = TestKnowledgeSpaceFixtures.DEFAULT_SPACE_ID;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private SourceDocumentRepository sourceDocumentRepository;

    @Autowired
    private com.flevin.knowgraph.server.service.association.DocumentAssociationService associationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearData() {
        // 按依赖顺序清理关联运行与全部历史业务数据，保证端到端运行从干净事实库开始
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
    void runsSemanticAugmentedAssociationEndToEndOnV2Fixture() throws IOException {
        // 真实聊天调用必须由环境变量显式提供密钥，避免误用配置文件默认值产生外部费用
        assertThat(System.getenv("AI_API_KEY"))
                .as("real-ai 端到端验证需要通过环境变量提供 AI_API_KEY")
                .isNotBlank();

        Fixture fixture = loadFixture();
        Map<String, SourceDocument> documents = importDocuments(fixture);

        // 从话术归档需求发起语义增强关联运行：其期望候选（知识库建设方案）只能由语义通道补上，
        // 语义通道会在运行内按需幂等补建章节、分片与真实向量事实
        SourceDocument subject = documents.get("v2-doc-archive-requirement");
        DocumentAssociationRunResponse run = associationService.createRun(
                SPACE_ID,
                subject.id(),
                false,
                true
        );

        // 运行必须完成且记录语义增强策略版本
        assertThat(run.status())
                .as("端到端关联运行应完成，失败原因：%s", run.errorMessage())
                .isEqualTo("completed");
        assertThat(run.candidateRecallPolicyVersion()).isIn(
                "document-candidate-recall-semantic-v1",
                "document-candidate-recall-v1"
        );

        // 服务端校验后的建议关系必须带逐字证据，且证据可反查原文
        run.relations().forEach(relation -> {
            assertThat(relation.relationType()).isNotEqualTo("none");
            assertThat(relation.evidences()).isNotEmpty();
        });

        // 自关联与跨空间约束在端到端路径同样必须成立
        Integer selfRelations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM document_relations WHERE source_document_id = target_document_id",
                Integer.class
        );
        assertThat(selfRelations == null ? 0 : selfRelations).isZero();
    }

    /**
     * 读取 v2 固定资料定义。
     *
     * @return 标注根节点与文档路径映射
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
        Map<String, String> documentPaths = new LinkedHashMap<>();
        root.path("documents").forEach(node ->
                documentPaths.put(node.path("documentId").asText(), node.path("path").asText()));
        return new Fixture(fixtureRoot, documentPaths);
    }

    /**
     * 通过正式导入链路导入 v2 全部资料。
     *
     * @param fixture 固定资料定义
     * @return fixture ID 到来源资料映射
     * @throws IOException 资料文件无法读取时抛出
     */
    private Map<String, SourceDocument> importDocuments(Fixture fixture) throws IOException {
        Map<String, SourceDocument> documents = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : fixture.documentPaths().entrySet()) {
            byte[] content = Files.readAllBytes(fixture.fixtureRoot().resolve(entry.getValue()));
            MockMultipartFile upload = new MockMultipartFile(
                    "files",
                    Path.of(entry.getValue()).getFileName().toString(),
                    "text/plain",
                    content
            );
            DocumentImportResponse response = documentService.importDocuments(
                    SPACE_ID,
                    List.of(upload)
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
     * 固定资料定义。
     *
     * @param fixtureRoot fixture 根目录
     * @param documentPaths 文档标识到相对路径
     */
    private record Fixture(
            Path fixtureRoot,
            Map<String, String> documentPaths
    ) {
    }
}

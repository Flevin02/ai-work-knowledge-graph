package com.flevin.knowgraph.server.documentgraph;

import com.flevin.knowgraph.server.model.document.DocumentImportResponse;
import com.flevin.knowgraph.server.model.document.SourceDocument;
import com.flevin.knowgraph.server.repository.document.SourceDocumentRepository;
import com.flevin.knowgraph.server.service.document.DocumentService;
import com.flevin.knowgraph.server.support.TestKnowledgeSpaceFixtures;
import com.flevin.knowgraph.server.support.TestIdFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 独立文档关系图查询接口集成测试。
 */
@SpringBootTest(properties = {
        "app.upload-dir=target/test-data/document-graph-controller-uploads"
})
@AutoConfigureMockMvc
class DocumentGraphControllerIntegrationTests {

    private static final Long SPACE_ID = TestKnowledgeSpaceFixtures.DEFAULT_SPACE_ID;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private SourceDocumentRepository sourceDocumentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearData() {
        // 按外键依赖顺序清理文档关系图测试数据，不影响其他测试数据库
        jdbcTemplate.update("DELETE FROM document_relation_reviews");
        jdbcTemplate.update("DELETE FROM document_relation_evidences");
        jdbcTemplate.update("DELETE FROM document_relations");
        jdbcTemplate.update("DELETE FROM document_association_runs");
        jdbcTemplate.update("DELETE FROM source_documents");
        jdbcTemplate.update("DELETE FROM import_batches");
        TestKnowledgeSpaceFixtures.ensureDefaultSpace(jdbcTemplate);
    }

    @Test
    void returnsRealDocumentNodesAndOnlyConfirmedRelations() throws Exception {
        SourceDocument source = importDocument(
                "会议纪要.md",
                "# 会议纪要\n会议依据年会方案讨论场地。"
        );
        SourceDocument target = importDocument(
                "年会方案.md",
                "# 年会方案\n方案记录场地和预算。"
        );
        Instant now = Instant.parse("2026-08-25T10:00:00Z");

        // 写入一条已确认关系和一条待审核关系，验证文档关系图默认只展示 confirmed
        Long confirmedRelationId = TestIdFixtures.id("confirmed-relation");
        insertRelation(confirmedRelationId, source, target, "confirmed", now);
        insertEvidence(
                TestIdFixtures.id("confirmed-evidence"),
                source,
                confirmedRelationId,
                "会议依据年会方案讨论场地。",
                now
        );
        insertRelation(TestIdFixtures.id("suggested-relation"), target, source, "suggested", now.plusSeconds(1));

        mockMvc.perform(get("/v1/spaces/{spaceId}/document-graph", SPACE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value(false))
                .andExpect(jsonPath("$.data.nodes.length()").value(2))
                .andExpect(jsonPath("$.data.nodes[?(@.name == '会议纪要.md')]").isNotEmpty())
                .andExpect(jsonPath("$.data.nodes[?(@.name == '年会方案.md')]").isNotEmpty())
                .andExpect(jsonPath("$.data.edges.length()").value(1))
                .andExpect(jsonPath("$.data.edges[0].id").value(confirmedRelationId))
                .andExpect(jsonPath("$.data.edges[0].status").value("confirmed"))
                .andExpect(jsonPath("$.data.edges[0].sourceDocumentId").value(source.id()))
                .andExpect(jsonPath("$.data.edges[0].targetDocumentId").value(target.id()))
                .andExpect(jsonPath("$.data.edges[0].evidences.length()").value(1))
                .andExpect(jsonPath("$.data.edges[0].evidences[0].quote")
                        .value("会议依据年会方案讨论场地。"));
    }

    @Test
    void exposesDocumentGraphOpenApiContract() throws Exception {
        // 检查独立文档关系图路径、标签和响应模型已经进入 OpenAPI 契约
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/v1/spaces/{spaceId}/document-graph'].get").exists())
                .andExpect(jsonPath("$.paths['/v1/spaces/{spaceId}/document-graph'].get.tags[0]")
                        .value("文档关系图"))
                .andExpect(jsonPath("$.components.schemas.DocumentGraphResponse").exists())
                .andExpect(jsonPath("$.components.schemas.DocumentGraphNodeResponse").exists())
                .andExpect(jsonPath("$.components.schemas.DocumentGraphEdgeResponse").exists());
    }

    private SourceDocument importDocument(String name, String content) {
        MockMultipartFile file = new MockMultipartFile(
                "files",
                name,
                "text/markdown",
                content.getBytes(StandardCharsets.UTF_8)
        );
        DocumentImportResponse response = documentService.importDocuments(SPACE_ID, List.of(file));
        return sourceDocumentRepository.findById(
                SPACE_ID,
                response.results().getFirst().document().id()
        ).orElseThrow();
    }

    private void insertRelation(
            Long id,
            SourceDocument source,
            SourceDocument target,
            String status,
            Instant updatedAt
    ) {
        jdbcTemplate.update(
                "INSERT INTO document_relations ("
                        + "id, space_id, source_document_id, target_document_id, relation_type, direction, "
                        + "status, generation_mode, confidence, reason, association_run_id, source_content_hash, "
                        + "target_content_hash, association_policy_version, relation_key, created_at, updated_at"
                        + ") VALUES (?, ?, ?, ?, 'references', 'current_to_candidate', ?, 'user', 0.95, ?, NULL, ?, ?, ?, ?, ?, ?)",
                id,
                SPACE_ID,
                source.id(),
                target.id(),
                status,
                "固定测试关系",
                source.contentHash(),
                target.contentHash(),
                "document-association-policy-v1",
                id + "-key",
                updatedAt.toString(),
                updatedAt.toString()
        );
    }

    private void insertEvidence(
            Long id,
            SourceDocument source,
            Long relationId,
            String quote,
            Instant createdAt
    ) {
        jdbcTemplate.update(
                "INSERT INTO document_relation_evidences ("
                        + "id, space_id, document_relation_id, source_document_id, chunk_id, section_path, "
                        + "quote, start_offset, end_offset, evidence_role, created_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id,
                SPACE_ID,
                relationId,
                source.id(),
                "chunk-1",
                "会议纪要",
                quote,
                0,
                quote.length(),
                "source",
                createdAt.toString()
        );
    }
}

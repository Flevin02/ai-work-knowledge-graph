package com.flevin.knowgraph.server.document;

import com.flevin.knowgraph.server.model.document.DocumentImportResponse;
import com.flevin.knowgraph.server.model.document.SourceDocument;
import com.flevin.knowgraph.server.repository.document.SourceDocumentRepository;
import com.flevin.knowgraph.server.service.document.DocumentService;
import com.flevin.knowgraph.server.support.TestIdFixtures;
import com.flevin.knowgraph.server.support.TestKnowledgeSpaceFixtures;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 来源资料版本更新（增量导入）接口集成测试。
 */
@SpringBootTest(properties = {
        "app.upload-dir=target/test-data/document-version-update-uploads"
})
@AutoConfigureMockMvc
class DocumentVersionUpdateIntegrationTests {

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
        // 按外键依赖顺序清理版本更新测试数据
        jdbcTemplate.update("DELETE FROM document_tag_evidences");
        jdbcTemplate.update("DELETE FROM document_tags");
        jdbcTemplate.update("DELETE FROM tags");
        jdbcTemplate.update("DELETE FROM document_relation_reviews");
        jdbcTemplate.update("DELETE FROM document_relation_evidences");
        jdbcTemplate.update("DELETE FROM document_relations");
        jdbcTemplate.update("DELETE FROM document_association_runs");
        jdbcTemplate.update("DELETE FROM source_documents");
        jdbcTemplate.update("DELETE FROM import_batches");
        TestKnowledgeSpaceFixtures.ensureDefaultSpace(jdbcTemplate);
    }

    @Test
    void returnsUnchangedWhenContentHashMatches() throws Exception {
        SourceDocument document = importDocument("版本测试.md", "# 版本测试\n原始内容。");

        // 上传相同内容的文件应返回 unchanged 且不产生任何变更
        mockMvc.perform(multipart("/v1/spaces/{spaceId}/documents/{documentId}/versions",
                                SPACE_ID, document.id())
                                .file(new MockMultipartFile("file", "版本测试.md", "text/markdown",
                                        "# 版本测试\n原始内容。".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("unchanged"))
                .andExpect(jsonPath("$.data.staleTagCount").value(0));
    }

    @Test
    void dryRunPreviewsChangeWithoutPersisting() throws Exception {
        SourceDocument document = importDocument("预览测试.md", "# 预览测试\n原始内容。");

        // 预览模式只返回统计，不更新事实源
        mockMvc.perform(multipart("/v1/spaces/{spaceId}/documents/{documentId}/versions",
                                SPACE_ID, document.id())
                                .file(new MockMultipartFile("file", "预览测试.md", "text/markdown",
                                        "# 预览测试\n内容已经发生变化。".getBytes(StandardCharsets.UTF_8)))
                                .param("dryRun", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("dry-run"))
                .andExpect(jsonPath("$.data.oldContentHash").value(document.contentHash()));

        // 预览后事实源保持不变
        SourceDocument afterPreview = sourceDocumentRepository.findById(SPACE_ID, document.id()).orElseThrow();
        assertThat(afterPreview.contentHash()).isEqualTo(document.contentHash());
        assertThat(afterPreview.contentText()).contains("原始内容");
    }

    @Test
    void updatesVersionAndFreezesStaleFacts() throws Exception {
        SourceDocument document = importDocument("增量导入.md", "# 增量导入\n原始内容。");
        Instant now = Instant.now();

        // 写入一条 confirmed 标签和一条 confirmed 文档关系，验证内容变更后均被冻结
        Long tagId = TestIdFixtures.id("version-tag");
        Long documentTagId = TestIdFixtures.id("version-document-tag");
        jdbcTemplate.update(
                "INSERT INTO tags (id, space_id, name, normalized_key, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'active', ?, ?)",
                tagId, SPACE_ID, "旧标签", "旧标签", now.toString(), now.toString()
        );
        jdbcTemplate.update(
                "INSERT INTO document_tags (id, space_id, source_document_id, tag_id, source_type, status, "
                        + "content_hash, document_tag_key, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'ai', 'confirmed', ?, ?, ?, ?)",
                documentTagId, SPACE_ID, document.id(), tagId, document.contentHash(),
                "version-document-tag-key", now.toString(), now.toString()
        );
        SourceDocument related = importDocument("关联文档.md", "# 关联文档\n用于关联的另一份文档。");
        Long relationId = TestIdFixtures.id("version-relation");
        jdbcTemplate.update(
                "INSERT INTO document_relations ("
                        + "id, space_id, source_document_id, target_document_id, relation_type, direction, "
                        + "status, generation_mode, confidence, reason, association_run_id, source_content_hash, "
                        + "target_content_hash, association_policy_version, relation_key, created_at, updated_at"
                        + ") VALUES (?, ?, ?, ?, 'references', 'current_to_candidate', 'confirmed', 'user', 0.9, ?, "
                        + "NULL, ?, ?, ?, ?, ?, ?)",
                relationId, SPACE_ID, document.id(), related.id(),
                "固定关联", document.contentHash(), related.contentHash(),
                "document-association-policy-v1", "version-relation-key", now.toString(), now.toString()
        );

        // 更新为不同内容：标签与关系均被冻结为 stale
        mockMvc.perform(multipart("/v1/spaces/{spaceId}/documents/{documentId}/versions",
                                SPACE_ID, document.id())
                                .file(new MockMultipartFile("file", "增量导入.md", "text/markdown",
                                        "# 增量导入\n内容已经完全变化。".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("updated"))
                .andExpect(jsonPath("$.data.staleTagCount").value(1))
                .andExpect(jsonPath("$.data.staleRelationCount").value(1));

        // 事实源已切换到新内容
        SourceDocument updated = sourceDocumentRepository.findById(SPACE_ID, document.id()).orElseThrow();
        assertThat(updated.contentHash()).isNotEqualTo(document.contentHash());
        assertThat(updated.contentText()).contains("内容已经完全变化");

        // 标签与关系状态为 stale
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM document_tags WHERE id = ?", String.class, documentTagId)).isEqualTo("stale");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM document_relations WHERE id = ?", String.class, relationId)).isEqualTo("stale");

        // 文档关系图不再展示 stale 关系，节点仍保留
        mockMvc.perform(get("/v1/spaces/{spaceId}/document-graph", SPACE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodes.length()").value(2))
                .andExpect(jsonPath("$.data.edges.length()").value(0));
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
}

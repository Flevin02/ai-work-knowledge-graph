package com.flevin.knowgraph.server.tag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flevin.knowgraph.server.model.document.DocumentImportResponse;
import com.flevin.knowgraph.server.model.document.SourceDocument;
import com.flevin.knowgraph.server.repository.document.SourceDocumentRepository;
import com.flevin.knowgraph.server.service.document.DocumentService;
import com.flevin.knowgraph.server.support.TestKnowledgeSpaceFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 标签运行创建、恢复和 OpenAPI REST 契约集成测试。
 */
@SpringBootTest(properties = {
        "app.database-path=target/test-data/document-tagging-controller.sqlite",
        "app.upload-dir=target/test-data/document-tagging-controller-uploads",
        "test.document-tagging-client=service"
})
@AutoConfigureMockMvc
@Import(DocumentTaggingServiceIntegrationTests.FakeTaggingConfiguration.class)
class DocumentTaggingControllerIntegrationTests {

    private static final String SPACE_ID = TestKnowledgeSpaceFixtures.DEFAULT_SPACE_ID;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private SourceDocumentRepository sourceDocumentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void clearData() {
        // 按外键依赖顺序清理标签运行和来源资料数据
        jdbcTemplate.update("DELETE FROM document_tag_evidences");
        jdbcTemplate.update("DELETE FROM document_tags");
        jdbcTemplate.update("DELETE FROM tags");
        jdbcTemplate.update("DELETE FROM document_tagging_runs");
        jdbcTemplate.update("DELETE FROM source_documents");
        jdbcTemplate.update("DELETE FROM import_batches");
        TestKnowledgeSpaceFixtures.ensureDefaultSpace(jdbcTemplate);
    }

    @Test
    void createsAndRestoresTaggingRun() throws Exception {
        SourceDocument document = importDocument(
                "标签接口.md",
                "# 会议纪要\n2026 年星桥科技年会筹备正式启动。"
        );

        mockMvc.perform(get(
                        "/v1/spaces/{spaceId}/documents/{documentId}/tagging-runs/latest",
                        SPACE_ID,
                        document.id()
                ))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.msg").value("文档标签运行不存在"));

        String responseJson = mockMvc.perform(post(
                        "/v1/spaces/{spaceId}/documents/{documentId}/tagging-runs",
                        SPACE_ID,
                        document.id()
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value(false))
                .andExpect(jsonPath("$.data.status").value("completed"))
                .andExpect(jsonPath("$.data.suggestions[0].name").value("年会筹备"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        JsonNode run = objectMapper.readTree(responseJson).path("data");

        mockMvc.perform(get(
                        "/v1/spaces/{spaceId}/documents/{documentId}/tagging-runs/latest",
                        SPACE_ID,
                        document.id()
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runId").value(run.path("runId").asText()))
                .andExpect(jsonPath("$.data.status").value("completed"));

        mockMvc.perform(get(
                        "/v1/spaces/{spaceId}/documents/{documentId}/tagging-runs/{runId}",
                        SPACE_ID,
                        document.id(),
                        run.path("runId").asText()
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runId").value(run.path("runId").asText()))
                .andExpect(jsonPath("$.data.suggestions[0].evidences.length()").value(1));

        // 查询运行时 OpenAPI，确认标签运行路径和具体响应模型已经发布
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.paths['/v1/spaces/{spaceId}/documents/{documentId}/tagging-runs'].post"
                ).exists())
                .andExpect(jsonPath(
                        "$.paths['/v1/spaces/{spaceId}/documents/{documentId}/tagging-runs/latest'].get"
                ).exists())
                .andExpect(jsonPath(
                        "$.paths['/v1/spaces/{spaceId}/documents/{documentId}/tagging-runs/{runId}'].get"
                ).exists())
                .andExpect(jsonPath("$.components.schemas.DocumentTaggingRunResponse").exists())
                .andExpect(jsonPath("$.components.schemas.DocumentTagSuggestionResponse").exists());
    }

    /**
     * 通过现有来源资料导入链路创建 HTTP 测试文档。
     *
     * @param name 虚构文件名
     * @param content 虚构原文
     * @return 已持久化来源资料
     */
    private SourceDocument importDocument(
            String name,
            String content
    ) {
        MockMultipartFile file = new MockMultipartFile(
                "files",
                name,
                "text/markdown",
                content.getBytes(StandardCharsets.UTF_8)
        );

        // 使用现有导入 Service 写入来源资料
        DocumentImportResponse response = documentService.importDocuments(SPACE_ID, List.of(file));

        // 查询完整来源资料用于后续标签运行
        return sourceDocumentRepository.findById(
                SPACE_ID,
                response.results().getFirst().document().id()
        ).orElseThrow();
    }
}

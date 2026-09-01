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
        "app.upload-dir=target/test-data/document-tagging-controller-uploads",
        "test.document-tagging-client=service"
})
@AutoConfigureMockMvc
@Import(DocumentTaggingServiceIntegrationTests.FakeTaggingConfiguration.class)
class DocumentTaggingControllerIntegrationTests {

    private static final Long SPACE_ID = TestKnowledgeSpaceFixtures.DEFAULT_SPACE_ID;

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
    void acceptsBatchTaggingAndCompletesEveryDocumentRun() throws Exception {
        SourceDocument first = importDocument(
                "标签批量一.md",
                "# 会议纪要\n2026 年星桥科技年会筹备正式启动。"
        );
        SourceDocument second = importDocument(
                "标签批量二.md",
                "# 项目周报\n本周完成知识库检索模块联调，下周进入权限分级开发。"
        );

        // 批量受理两张资料，由服务端有界线程池并发创建独立标签运行
        mockMvc.perform(post(
                        "/v1/spaces/{spaceId}/documents/tagging-batches",
                        SPACE_ID
                ).contentType("application/json")
                .content(objectMapper.writeValueAsString(java.util.Map.of(
                        "documentIds", List.of(first.id(), second.id())
                ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value(false))
                .andExpect(jsonPath("$.data.requestedCount").value(2))
                .andExpect(jsonPath("$.data.acceptedCount").value(2))
                .andExpect(jsonPath("$.data.rejectedDocumentIds.length()").value(0));

        // 后台运行由 Fake 客户端快速完成，轮询恢复每份资料的最近运行状态
        for (SourceDocument document : List.of(first, second)) {
            long deadline = System.currentTimeMillis() + 10_000;
            String latestStatus = null;
            while (System.currentTimeMillis() < deadline) {
                var latestResult = mockMvc.perform(get(
                                "/v1/spaces/{spaceId}/documents/{documentId}/tagging-runs/latest",
                                SPACE_ID,
                                document.id()
                        ))
                        .andReturn();
                if (latestResult.getResponse().getStatus() == 200) {
                    latestStatus = objectMapper.readTree(latestResult.getResponse()
                            .getContentAsString(StandardCharsets.UTF_8))
                            .path("data").path("status").asText();
                    if ("completed".equals(latestStatus) || "failed".equals(latestStatus)) {
                        break;
                    }
                }
                Thread.sleep(100);
            }
            mockMvc.perform(get(
                            "/v1/spaces/{spaceId}/documents/{documentId}/tagging-runs/latest",
                            SPACE_ID,
                            document.id()
                    ))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("completed"))
                    .andExpect(jsonPath("$.data.suggestions[0].name").value("年会筹备"));
        }

        // 重复提交同一份资料被参数校验拒绝
        mockMvc.perform(post(
                        "/v1/spaces/{spaceId}/documents/tagging-batches",
                        SPACE_ID
                ).contentType("application/json")
                .content(objectMapper.writeValueAsString(java.util.Map.of(
                        "documentIds", List.of(first.id(), first.id())
                ))))
                .andExpect(status().is4xxClientError());
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

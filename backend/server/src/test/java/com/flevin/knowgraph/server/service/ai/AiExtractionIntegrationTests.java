package com.flevin.knowgraph.server.service.ai;

import com.flevin.knowgraph.server.model.ai.AiEntityCandidate;
import com.flevin.knowgraph.server.model.ai.AiEntityType;
import com.flevin.knowgraph.server.model.ai.AiEvidenceCandidate;
import com.flevin.knowgraph.server.model.ai.AiExtractionRequest;
import com.flevin.knowgraph.server.model.ai.AiExtractionResult;
import com.flevin.knowgraph.server.model.ai.AiRelationCandidate;
import com.flevin.knowgraph.server.model.document.DocumentImportResponse;
import com.flevin.knowgraph.server.service.document.DocumentService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 抽取入口集成测试，使用 Fake/Mock 客户端验证编排边界，不调用真实模型。
 */
@SpringBootTest(properties = {
        "app.database-path=target/test-data/ai-extraction.sqlite",
        "app.upload-dir=target/test-data/ai-extraction-uploads",
        "ai.enabled=false"
})
@AutoConfigureMockMvc
class AiExtractionIntegrationTests {

    private static final String SPACE_ID = "default-space";

    @Autowired
    private DocumentService documentService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private AiExtractionClient aiExtractionClient;

    @BeforeEach
    void clearPreviousExtractionData() {
        // 先清理引用来源资料的抽取、证据、关系和节点，满足 SQLite 外键约束
        jdbcTemplate.update("DELETE FROM ai_extraction_runs");
        jdbcTemplate.update("DELETE FROM review_actions");
        jdbcTemplate.update("DELETE FROM evidences");
        jdbcTemplate.update("DELETE FROM graph_edges");
        jdbcTemplate.update("DELETE FROM graph_nodes");
        jdbcTemplate.update("DELETE FROM source_documents");
        jdbcTemplate.update("DELETE FROM import_batches");
    }

    @Test
    void extractsImportedDocumentThroughPreviewEndpointWithoutWritingGraph() throws Exception {
        MockMultipartFile documentFile = new MockMultipartFile(
                "files",
                "登录功能.md",
                "text/markdown",
                "# 用户中心\n\n## 登录功能\n登录功能支持手机号验证码。".getBytes(StandardCharsets.UTF_8)
        );

        // 先导入来源资料，准备真实章节和分片数据
        DocumentImportResponse importResponse = documentService.importDocuments(
                SPACE_ID,
                "prd",
                List.of(documentFile)
        );
        String documentId = importResponse.results().getFirst().document().id();

        // 使用固定结构化结果替代真实模型，验证服务端编排和证据校验
        when(aiExtractionClient.extract(any(AiExtractionRequest.class)))
                .thenAnswer(invocation -> fakeResult(invocation.getArgument(0)));

        // 调用 AI 抽取预览入口，不写入正式图谱表
        MvcResult extractionResult = mockMvc.perform(post(
                        "/v1/spaces/{spaceId}/documents/{documentId}/extractions",
                        SPACE_ID,
                        documentId
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value(false))
                .andExpect(jsonPath("$.data.documentType").value("prd"))
                .andExpect(jsonPath("$.data.sectionCount").value(2))
                .andExpect(jsonPath("$.data.chunkCount").value(2))
                .andExpect(jsonPath("$.data.summary").value("用户中心；登录功能支持手机号验证码。"))
                .andExpect(jsonPath("$.data.chunks[0].extraction.entities.length()").value(2))
                .andReturn();

        JsonNode extractionJson = objectMapper.readTree(extractionResult.getResponse().getContentAsString());
        String extractionId = extractionJson.path("data").path("extractionId").asText();

        // 查询来源资料分页列表，验证最近成功抽取状态已随列表首屏返回
        mockMvc.perform(get("/v1/spaces/{spaceId}/documents", SPACE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].latestExtraction.extractionId").value(extractionId))
                .andExpect(jsonPath("$.data.items[0].latestExtraction.status").value("completed"))
                .andExpect(jsonPath("$.data.items[0].excerpt").value("用户中心；登录功能支持手机号验证码。"))
                .andExpect(jsonPath("$.data.items[0].latestCompletedExtractionId").value(extractionId));

        String failedExtractionId = "failed-after-completed";

        // 写入一条更新的失败运行，验证最近状态和可查看历史成功结果彼此独立
        jdbcTemplate.update(
                """
                INSERT INTO ai_extraction_runs (
                    id, space_id, source_document_id, provider, model,
                    prompt_version, schema_version, status, section_count,
                    chunk_count, error_message, created_at, completed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                failedExtractionId,
                SPACE_ID,
                documentId,
                "fake",
                "fake-model",
                "prompt-v1",
                "schema-v1",
                "failed",
                0,
                0,
                "模型返回内容未通过结构校验",
                Instant.now().plusSeconds(60).toString(),
                Instant.now().plusSeconds(61).toString()
        );

        // 再次查询列表，验证本次失败不会覆盖最近一次可用成功结果标识
        mockMvc.perform(get("/v1/spaces/{spaceId}/documents", SPACE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].latestExtraction.extractionId").value(failedExtractionId))
                .andExpect(jsonPath("$.data.items[0].latestExtraction.status").value("failed"))
                .andExpect(jsonPath("$.data.items[0].latestExtraction.errorMessage").value("模型返回内容未通过结构校验"))
                .andExpect(jsonPath("$.data.items[0].excerpt").value("用户中心；登录功能支持手机号验证码。"))
                .andExpect(jsonPath("$.data.items[0].latestCompletedExtractionId").value(extractionId));

        // 查询历史抽取记录摘要，验证最近失败和此前成功结果都保持可追溯
        mockMvc.perform(get(
                        "/v1/spaces/{spaceId}/documents/{documentId}/extractions",
                        SPACE_ID,
                        documentId
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].extractionId").value(failedExtractionId))
                .andExpect(jsonPath("$.data[0].status").value("failed"))
                .andExpect(jsonPath("$.data[1].extractionId").value(extractionId))
                .andExpect(jsonPath("$.data[1].status").value("completed"));

        // 查询历史抽取完整结果，验证页面刷新后仍可重新打开候选结果
        mockMvc.perform(get(
                        "/v1/spaces/{spaceId}/documents/{documentId}/extractions/{extractionId}",
                        SPACE_ID,
                        documentId,
                        extractionId
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.extractionId").value(extractionId))
                .andExpect(jsonPath("$.data.result.documentId").value(documentId))
                .andExpect(jsonPath("$.data.result.summary").value("用户中心；登录功能支持手机号验证码。"))
                .andExpect(jsonPath("$.data.result.chunks.length()").value(2));
    }

    @Test
    void fallsBackToImportedExcerptWhenHistoricalCompletedRunHasNoSummary() throws Exception {
        MockMultipartFile documentFile = new MockMultipartFile(
                "files",
                "旧版资料.txt",
                "text/plain",
                "旧版资料仍应展示导入时生成的原文预览。".getBytes(StandardCharsets.UTF_8)
        );

        // 导入来源资料，准备确定性的原文预览兜底值
        DocumentImportResponse importResponse = documentService.importDocuments(
                SPACE_ID,
                "general",
                List.of(documentFile)
        );
        String documentId = importResponse.results().getFirst().document().id();
        String extractionId = "legacy-completed-without-summary";

        // 模拟升级前已完成但尚未保存 document_summary 的历史抽取运行
        jdbcTemplate.update(
                """
                INSERT INTO ai_extraction_runs (
                    id, space_id, source_document_id, provider, model,
                    prompt_version, schema_version, status, section_count,
                    chunk_count, result_json, created_at, completed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                extractionId,
                SPACE_ID,
                documentId,
                "fake",
                "fake-model",
                "prd-extraction-v1",
                "extraction-v1",
                "completed",
                1,
                1,
                "{}",
                Instant.now().toString(),
                Instant.now().plusSeconds(1).toString()
        );

        // 查询资料列表，验证旧成功运行无摘要时仍返回导入原文预览
        mockMvc.perform(get("/v1/spaces/{spaceId}/documents", SPACE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].excerpt").value("旧版资料仍应展示导入时生成的原文预览。"))
                .andExpect(jsonPath("$.data.items[0].latestCompletedExtractionId").value(extractionId));
    }

    private AiExtractionResult fakeResult(AiExtractionRequest request) {
        AiEvidenceCandidate evidence = new AiEvidenceCandidate(
                "evidence-1",
                request.sourceDocumentId(),
                request.chunkId(),
                request.sectionPath(),
                request.content().contains("登录功能支持手机号验证码")
                        ? "登录功能支持手机号验证码。"
                        : request.content().substring(0, Math.min(8, request.content().length()))
        );
        AiEntityCandidate project = new AiEntityCandidate(
                "entity-project",
                AiEntityType.PROJECT,
                "用户中心",
                "用户中心项目",
                List.of("evidence-1")
        );
        AiEntityCandidate feature = new AiEntityCandidate(
                "entity-feature",
                AiEntityType.FEATURE,
                "登录功能",
                "支持手机号验证码登录",
                List.of("evidence-1")
        );
        AiRelationCandidate relation = new AiRelationCandidate(
                "entity-project",
                "entity-feature",
                "project_contains_feature",
                0.9D,
                List.of("evidence-1")
        );
        return new AiExtractionResult(
                request.content().contains("登录功能支持手机号验证码")
                        ? "登录功能支持手机号验证码。"
                        : "用户中心",
                List.of(project, feature),
                List.of(relation),
                List.of(evidence),
                List.of()
        );
    }
}

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 抽取入口集成测试，使用 Fake/Mock 客户端验证编排边界，不调用真实模型。
 */
@SpringBootTest(properties = {
        "app.database-path=target/test-data/ai-extraction.sqlite",
        "app.upload-dir=target/test-data/ai-extraction-uploads",
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.minimum-idle=1",
        "spring.datasource.hikari.connection-timeout=500",
        "spring.datasource.hikari.initialization-fail-timeout=500",
        "ai.batch-extraction.max-concurrency=2",
        "ai.batch-extraction.queue-capacity=4",
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
    void extractsImportedDocumentMaterializesCandidatesAndSupportsRelationReview() throws Exception {
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
        when(aiExtractionClient.extract(
                any(AiExtractionRequest.class),
                org.mockito.ArgumentMatchers.<Consumer<String>>any()
        )).thenAnswer(invocation -> {
            Consumer<String> deltaConsumer = invocation.getArgument(1);

            // 转发 Fake 模型真实提供的固定增量，验证 SSE delta 不由服务端伪造
            deltaConsumer.accept("{\"summary\":");
            return fakeResult(invocation.getArgument(0));
        });

        // 调用 AI 抽取 SSE 入口，读取完成前的全部运行事件
        String extractionStream = performStreamingExtraction(documentId);

        assertThat(extractionStream.indexOf("event:run_started"))
                .isLessThan(extractionStream.indexOf("event:chunk_started"));
        assertThat(extractionStream.indexOf("event:chunk_started"))
                .isLessThan(extractionStream.indexOf("event:delta"));
        assertThat(extractionStream.indexOf("event:delta"))
                .isLessThan(extractionStream.indexOf("event:chunk_completed"));
        assertThat(extractionStream.indexOf("event:chunk_completed"))
                .isLessThan(extractionStream.indexOf("event:completed"));
        assertThat(countStreamEvents(extractionStream, "chunk_started")).isEqualTo(2);
        assertThat(countStreamEvents(extractionStream, "chunk_completed")).isEqualTo(2);

        // 解析终止事件，验证完整结果仍在服务端校验和持久化后返回
        JsonNode completedEvent = readStreamEvent(extractionStream, "completed");
        String extractionId = completedEvent.path("extractionRunId").asText();
        assertThat(completedEvent.path("result").path("documentType").asText()).isEqualTo("prd");
        assertThat(completedEvent.path("result").path("sectionCount").asInt()).isEqualTo(2);
        assertThat(completedEvent.path("result").path("chunkCount").asInt()).isEqualTo(2);
        assertThat(completedEvent.path("result").path("summary").asText())
                .isEqualTo("用户中心；登录功能支持手机号验证码。");
        assertThat(completedEvent.path("result").path("chunks").get(0)
                .path("extraction").path("entities").size()).isEqualTo(2);

        // 抽取完成后候选节点、关系和证据已经进入真实图谱，但关系仍待人工审核
        mockMvc.perform(get("/v1/spaces/{spaceId}/graph/summary", SPACE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodes").value(2))
                .andExpect(jsonPath("$.data.edges").value(0))
                .andExpect(jsonPath("$.data.pendingReviews").value(1));

        // 只提交服务端结果中的分片标识和关系顺序，验证审核接口不信任前端主体客体
        String firstChunkId = completedEvent.path("result").path("chunks").get(0)
                .path("chunkId").asText();
        mockMvc.perform(post(
                        "/v1/spaces/{spaceId}/documents/{documentId}/extractions/{extractionId}/reviews",
                        SPACE_ID,
                        documentId,
                        extractionId
                )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reviews": [{
                                    "chunkId": "%s",
                                    "relationIndex": 0,
                                    "action": "ACCEPT"
                                  }],
                                  "operatorName": "test-user"
                                }
                                """.formatted(firstChunkId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.acceptedCount").value(1))
                .andExpect(jsonPath("$.data.rejectedCount").value(0))
                .andExpect(jsonPath("$.data.pendingCount").value(0));

        // 审核后关系成为正式关系，并保留一条不可变审核动作记录
        mockMvc.perform(get("/v1/spaces/{spaceId}/graph/summary", SPACE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.edges").value(1))
                .andExpect(jsonPath("$.data.pendingReviews").value(0));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM review_actions WHERE operator_name = ?",
                Integer.class,
                "test-user"
        )).isEqualTo(1);
        mockMvc.perform(get(
                        "/v1/spaces/{spaceId}/documents/{documentId}/extractions/{extractionId}/reviews",
                        SPACE_ID,
                        documentId,
                        extractionId
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].chunkId").value(firstChunkId))
                .andExpect(jsonPath("$.data[0].relationIndex").value(0))
                .andExpect(jsonPath("$.data[0].action").value("ACCEPT"));

        JsonNode deltaEvent = readStreamEvent(extractionStream, "delta");
        assertThat(deltaEvent.path("delta").asText()).isEqualTo("{\"summary\":");

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

    @Test
    void blockingModelCallDoesNotHoldTheOnlyDatabaseConnection() throws Exception {
        MockMultipartFile extractionFile = new MockMultipartFile(
                "files",
                "并发抽取资料.txt",
                "text/plain",
                "并发抽取期间需要允许资料导入和查看。".getBytes(StandardCharsets.UTF_8)
        );

        // 先导入待抽取资料，准备真实来源记录和单分片原文
        DocumentImportResponse importResponse = documentService.importDocuments(
                SPACE_ID,
                "general",
                List.of(extractionFile)
        );
        String documentId = importResponse.results().getFirst().document().id();
        CountDownLatch modelCallStarted = new CountDownLatch(1);
        CountDownLatch releaseModelCall = new CountDownLatch(1);

        // 阻塞 Fake 模型调用，模拟真实模型长耗时但不占用数据库事务
        when(aiExtractionClient.extract(
                any(AiExtractionRequest.class),
                org.mockito.ArgumentMatchers.<Consumer<String>>any()
        ))
                .thenAnswer(invocation -> {
                    modelCallStarted.countDown();
                    if (!releaseModelCall.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("等待释放 Fake 模型调用超时");
                    }
                    return fakeResult(invocation.getArgument(0));
                });

        // 在独立线程触发抽取，主测试线程同时验证唯一池连接仍可服务其他请求
        try (ExecutorService executorService = Executors.newSingleThreadExecutor()) {
            Future<MvcResult> extractionFuture = executorService.submit(() -> {
                MvcResult streamStarted = mockMvc.perform(post(
                            "/v1/spaces/{spaceId}/documents/{documentId}/extractions",
                            SPACE_ID,
                            documentId
                        ).accept("text/event-stream"))
                        .andExpect(request().asyncStarted())
                        .andReturn();

                // 等待异步 SSE 抽取完成，确保最终运行记录和终止事件均已收口
                return mockMvc.perform(asyncDispatch(streamStarted))
                        .andExpect(status().isOk())
                        .andReturn();
            });

            try {
                // 等待抽取运行记录已保存且模型调用进入阻塞阶段
                assertThat(modelCallStarted.await(2, TimeUnit.SECONDS)).isTrue();

                MockMultipartFile concurrentFile = new MockMultipartFile(
                        "files",
                        "并发导入资料.txt",
                        "text/plain",
                        "模型处理期间导入的另一份虚构资料。".getBytes(StandardCharsets.UTF_8)
                );

                // 池上限为 1 时仍应完成另一份资料的查重、保存和批次更新
                DocumentImportResponse concurrentImportResponse = documentService.importDocuments(
                        SPACE_ID,
                        "general",
                        List.of(concurrentFile)
                );
                assertThat(concurrentImportResponse.importedCount()).isEqualTo(1);

                // 同一阻塞窗口内查询资料列表，确认 processing 状态和两份资料均可读取
                mockMvc.perform(get("/v1/spaces/{spaceId}/documents", SPACE_ID))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.total").value(2))
                        .andExpect(jsonPath("$.data.items[1].latestExtraction.status").value("processing"));
            } finally {
                // 无论并发断言是否成功都释放 Fake 模型，避免后台测试线程悬挂
                releaseModelCall.countDown();
            }

            // 等待抽取完成，确认阻塞恢复后运行记录能够正常收口
            extractionFuture.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void batchExtractionUsesBackendThreadPoolAndPersistsIndependentRuns() throws Exception {
        MockMultipartFile firstFile = new MockMultipartFile(
                "files",
                "批量提取一.md",
                "text/markdown",
                "# 批量提取一\n\n第一份资料用于验证后台并发抽取。".getBytes(StandardCharsets.UTF_8)
        );
        MockMultipartFile secondFile = new MockMultipartFile(
                "files",
                "批量提取二.md",
                "text/markdown",
                "# 批量提取二\n\n第二份资料用于验证后台并发抽取。".getBytes(StandardCharsets.UTF_8)
        );

        // 导入两份资料，准备由一个批量接口受理的独立抽取任务
        DocumentImportResponse importResponse = documentService.importDocuments(
                SPACE_ID,
                "prd",
                List.of(firstFile, secondFile)
        );
        List<String> documentIds = importResponse.results().stream()
                .map(result -> result.document().id())
                .toList();
        CountDownLatch modelCallsStarted = new CountDownLatch(2);
        CountDownLatch releaseModelCalls = new CountDownLatch(1);

        // 同时阻塞两次 Fake 模型调用，验证批量任务由后端线程池并发执行而非前端多请求串行等待
        when(aiExtractionClient.extract(
                any(AiExtractionRequest.class),
                org.mockito.ArgumentMatchers.<Consumer<String>>any()
        )).thenAnswer(invocation -> {
            modelCallsStarted.countDown();
            if (!releaseModelCalls.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待释放批量 Fake 模型调用超时");
            }
            return fakeResult(invocation.getArgument(0));
        });

        try {
            // 一次请求受理两份资料，前端无需为批量提取维持多条 SSE 连接
            mockMvc.perform(post("/v1/spaces/{spaceId}/documents/extraction-batches", SPACE_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(java.util.Map.of("documentIds", documentIds))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.error").value(false))
                    .andExpect(jsonPath("$.data.requestedCount").value(2))
                    .andExpect(jsonPath("$.data.acceptedCount").value(2))
                    .andExpect(jsonPath("$.data.documentIds.length()").value(2))
                    .andExpect(jsonPath("$.data.rejectedDocumentIds.length()").value(0));

            // 等待两份资料同时进入模型调用，证明线程池实际达到了配置并发度
            assertThat(modelCallsStarted.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            // 无论断言是否成功都释放 Fake 模型任务，避免后台线程影响后续测试
            releaseModelCalls.countDown();
        }

        // 等待两个独立任务各自完成并写入可恢复的运行记录
        waitForCompletedBatchExtractions(2);
        Integer completedRunCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_extraction_runs WHERE status = 'completed'",
                Integer.class
        );
        assertThat(completedRunCount).isEqualTo(2);
    }

    @Test
    void keepsPartialDeltaOutOfPersistedResultWhenValidationFails() throws Exception {
        MockMultipartFile documentFile = new MockMultipartFile(
                "files",
                "结构校验失败.md",
                "text/markdown",
                "# 风险\n\n模型部分输出不能写入正式结果。".getBytes(StandardCharsets.UTF_8)
        );

        // 导入单分片虚构资料，准备可追溯的失败运行
        DocumentImportResponse importResponse = documentService.importDocuments(
                SPACE_ID,
                "prd",
                List.of(documentFile)
        );
        String documentId = importResponse.results().getFirst().document().id();

        // Fake 模型先返回真实部分文本，再模拟完整结构校验失败
        when(aiExtractionClient.extract(
                any(AiExtractionRequest.class),
                org.mockito.ArgumentMatchers.<Consumer<String>>any()
        )).thenAnswer(invocation -> {
            Consumer<String> deltaConsumer = invocation.getArgument(1);
            deltaConsumer.accept("{\"summary\":\"未完成");
            throw new AiExtractionValidationException("Fake 完整结果无效");
        });

        // 消费完整 SSE 响应，验证失败前的真实增量和稳定错误事件都可见
        String extractionStream = performStreamingExtraction(documentId);
        assertThat(extractionStream).contains("event:delta");
        assertThat(extractionStream).contains("event:error");
        assertThat(extractionStream).doesNotContain("event:completed");
        assertThat(extractionStream.indexOf("event:delta"))
                .isLessThan(extractionStream.indexOf("event:error"));

        JsonNode errorEvent = readStreamEvent(extractionStream, "error");
        String extractionId = errorEvent.path("extractionRunId").asText();
        assertThat(errorEvent.path("recoverable").asBoolean()).isTrue();
        assertThat(errorEvent.path("message").asText()).isEqualTo("AI 返回的结构化结果未通过证据校验");

        // 查询运行记录，确认部分 JSON 没有进入完整结果字段且失败原因可恢复
        mockMvc.perform(get(
                        "/v1/spaces/{spaceId}/documents/{documentId}/extractions/{extractionId}",
                        SPACE_ID,
                        documentId,
                        extractionId
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.status").value("failed"))
                .andExpect(jsonPath("$.data.summary.chunkCount").value(1))
                .andExpect(jsonPath("$.data.summary.errorMessage").value("AI 返回的结构化结果未通过证据校验"))
                .andExpect(jsonPath("$.data.result").doesNotExist());

        Integer storedResultCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_extraction_runs WHERE id = ? AND result_json IS NOT NULL",
                Integer.class,
                extractionId
        );
        assertThat(storedResultCount).isZero();
    }

    @Test
    void openApiPublishesStreamingExtractionContract() throws Exception {
        // 查询运行时 OpenAPI，确认抽取资源明确声明 SSE 响应媒体类型
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.paths['/v1/spaces/{spaceId}/documents/{documentId}/extractions']"
                                + ".post.responses['200'].content['text/event-stream']"
                ).exists());
    }

    /**
     * 等待后台批量抽取任务完成，避免把接口受理成功误当作模型和持久化已经完成。
     *
     * @param expectedCompletedCount 预期完成的抽取运行数量
     * @throws InterruptedException 等待期间线程被中断时抛出
     */
    private void waitForCompletedBatchExtractions(int expectedCompletedCount) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadlineNanos) {
            Integer completedCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM ai_extraction_runs WHERE status = 'completed'",
                    Integer.class
            );
            if (completedCount != null && completedCount >= expectedCompletedCount) {
                return;
            }

            // 短暂等待后台线程完成模型返回后的 SQLite 写入收口
            Thread.sleep(50);
        }

        Integer completedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_extraction_runs WHERE status = 'completed'",
                Integer.class
        );
        assertThat(completedCount).isGreaterThanOrEqualTo(expectedCompletedCount);
    }

    private String performStreamingExtraction(String documentId) throws Exception {
        // 发起 SSE 请求并确认 Spring MVC 已切换到异步响应
        MvcResult streamStarted = mockMvc.perform(post(
                        "/v1/spaces/{spaceId}/documents/{documentId}/extractions",
                        SPACE_ID,
                        documentId
                ).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();

        // 等待流式任务结束并读取完整测试响应缓冲区
        MvcResult streamCompleted = mockMvc.perform(asyncDispatch(streamStarted))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn();
        return streamCompleted.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private JsonNode readStreamEvent(
            String extractionStream,
            String eventName
    ) throws Exception {
        String eventPrefix = "event:" + eventName;
        for (String eventBlock : extractionStream.split("\\n\\n")) {
            if (!eventBlock.startsWith(eventPrefix)) {
                continue;
            }
            for (String line : eventBlock.split("\\n")) {
                if (line.startsWith("data:")) {
                    // 解析单行 JSON 载荷，SSE 写出器会转义模型文本中的换行符
                    return objectMapper.readTree(line.substring("data:".length()));
                }
            }
        }
        throw new AssertionError("未找到 SSE 事件: " + eventName);
    }

    private int countStreamEvents(
            String extractionStream,
            String eventName
    ) {
        return extractionStream.split("event:" + eventName + "\\n", -1).length - 1;
    }

    private AiExtractionResult fakeResult(AiExtractionRequest request) {
        String entitySummary = "用户中心包含登录功能，登录功能支持手机号验证码登录。".repeat(4);
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
                entitySummary,
                List.of("evidence-1")
        );
        AiEntityCandidate feature = new AiEntityCandidate(
                "entity-feature",
                AiEntityType.FEATURE,
                "登录功能",
                entitySummary,
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

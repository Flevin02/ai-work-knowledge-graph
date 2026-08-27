package com.flevin.knowgraph.server.tag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flevin.knowgraph.server.model.document.DocumentImportResponse;
import com.flevin.knowgraph.server.model.document.SourceDocument;
import com.flevin.knowgraph.server.model.tag.DocumentTag;
import com.flevin.knowgraph.server.model.tag.DocumentTagEvidence;
import com.flevin.knowgraph.server.model.tag.DocumentTagResponse;
import com.flevin.knowgraph.server.model.tag.KnowledgeTag;
import com.flevin.knowgraph.server.repository.document.SourceDocumentRepository;
import com.flevin.knowgraph.server.service.document.DocumentService;
import com.flevin.knowgraph.server.service.tag.DocumentTagPersistenceService;
import com.flevin.knowgraph.server.service.tag.DocumentTagService;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文档标签查询、不可变审核历史、批量审核和 OpenAPI 契约集成测试。
 */
@SpringBootTest(properties = {
        "app.upload-dir=target/test-data/document-tag-review-controller-uploads"
})
@AutoConfigureMockMvc
class DocumentTagReviewControllerIntegrationTests {

    private static final Long SPACE_ID = TestKnowledgeSpaceFixtures.DEFAULT_SPACE_ID;
    private static final String PROMPT_VERSION = "document-tag-v1";
    private static final String SCHEMA_VERSION = "document-tag-v1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private SourceDocumentRepository sourceDocumentRepository;

    @Autowired
    private DocumentTagPersistenceService persistenceService;

    @Autowired
    private DocumentTagService documentTagService;

    @BeforeEach
    void clearData() {
        // 按外键依赖顺序清理标签审核、证据、关系、字典和来源资料
        jdbcTemplate.update("DELETE FROM document_tag_reviews");
        jdbcTemplate.update("DELETE FROM document_tag_evidences");
        jdbcTemplate.update("DELETE FROM document_tags");
        jdbcTemplate.update("DELETE FROM tags");
        jdbcTemplate.update("DELETE FROM document_tagging_runs");
        jdbcTemplate.update("DELETE FROM source_documents");
        jdbcTemplate.update("DELETE FROM import_batches");

        // 恢复当前测试使用的固定知识空间
        TestKnowledgeSpaceFixtures.ensureDefaultSpace(jdbcTemplate);
    }

    @Test
    void listsTagsReviewsBatchAndConfirmedSpaceSummaries() throws Exception {
        Set<String> tableNames = Set.copyOf(jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()",
                String.class
        ));
        assertThat(tableNames).contains("document_tag_reviews");

        SourceDocument document = importDocument(
                "年会标签审核.md",
                "# 筹备主题\n星桥科技年会筹备正式启动，现场执行方案同步确认。"
        );
        DocumentTag annualMeeting = saveSuggestion(
                "annual-meeting",
                "年会筹备",
                document,
                "年会筹备正式启动"
        );
        DocumentTag execution = saveSuggestion(
                "execution",
                "现场执行",
                document,
                "现场执行方案"
        );

        mockMvc.perform(get(
                        "/v1/spaces/{spaceId}/documents/{documentId}/tags",
                        SPACE_ID,
                        document.id()
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[*].status").value(containsInAnyOrder(
                        "suggested",
                        "suggested"
                )))
                .andExpect(jsonPath("$.data[*].evidences.length()").value(containsInAnyOrder(1, 1)))
                .andExpect(jsonPath("$.data[*].reviews.length()").value(containsInAnyOrder(0, 0)));

        String reviewJson = "{\"reviews\":["
                + "{\"documentTagId\":\"" + annualMeeting.id()
                + "\",\"action\":\"accept\",\"reason\":\"主题明确\"},"
                + "{\"documentTagId\":\"" + execution.id()
                + "\",\"action\":\"reject\",\"reason\":\"范围过宽\"}]}";
        mockMvc.perform(post(
                        "/v1/spaces/{spaceId}/documents/{documentId}/tag-review-batches",
                        SPACE_ID,
                        document.id()
                ).contentType("application/json").content(reviewJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.acceptedCount").value(1))
                .andExpect(jsonPath("$.data.rejectedCount").value(1))
                .andExpect(jsonPath("$.data.tags[*].status").value(containsInAnyOrder(
                        "confirmed",
                        "rejected"
                )))
                .andExpect(jsonPath("$.data.tags[*].reviews.length()").value(containsInAnyOrder(1, 1)));

        mockMvc.perform(get("/v1/spaces/{spaceId}/tags", SPACE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("年会筹备"))
                .andExpect(jsonPath("$.data[0].documentCount").value(1));

        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.paths['/v1/spaces/{spaceId}/documents/{documentId}/tags'].get"
                ).exists())
                .andExpect(jsonPath(
                        "$.paths['/v1/spaces/{spaceId}/documents/{documentId}/tag-review-batches'].post"
                ).exists())
                .andExpect(jsonPath("$.paths['/v1/spaces/{spaceId}/tags'].get").exists())
                .andExpect(jsonPath("$.components.schemas.DocumentTagResponse").exists())
                .andExpect(jsonPath("$.components.schemas.DocumentTagReviewBatchRequest").exists())
                .andExpect(jsonPath("$.components.schemas.DocumentTagReviewBatchResponse").exists())
                .andExpect(jsonPath("$.components.schemas.KnowledgeTagSummaryResponse").exists());
    }

    @Test
    void rejectsCrossDocumentAndDuplicateBatchWithoutPartialReview() throws Exception {
        SourceDocument currentDocument = importDocument(
                "当前文档.md",
                "当前文档包含年会预算说明。"
        );
        SourceDocument otherDocument = importDocument(
                "其他文档.md",
                "其他文档包含场地合同说明。"
        );
        DocumentTag currentTag = saveSuggestion(
                "budget",
                "年会预算",
                currentDocument,
                "年会预算"
        );
        DocumentTag otherTag = saveSuggestion(
                "venue",
                "场地合同",
                otherDocument,
                "场地合同"
        );

        String crossDocumentJson = "{\"reviews\":["
                + "{\"documentTagId\":\"" + currentTag.id() + "\",\"action\":\"accept\"},"
                + "{\"documentTagId\":\"" + otherTag.id() + "\",\"action\":\"reject\"}]}";
        mockMvc.perform(post(
                        "/v1/spaces/{spaceId}/documents/{documentId}/tag-review-batches",
                        SPACE_ID,
                        currentDocument.id()
                ).contentType("application/json").content(crossDocumentJson))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(true))
                .andExpect(jsonPath("$.msg").value("文档标签不属于当前来源资料"));

        String duplicateJson = "{\"reviews\":["
                + "{\"documentTagId\":\"" + currentTag.id() + "\",\"action\":\"accept\"},"
                + "{\"documentTagId\":\"" + currentTag.id() + "\",\"action\":\"reject\"}]}";
        mockMvc.perform(post(
                        "/v1/spaces/{spaceId}/documents/{documentId}/tag-review-batches",
                        SPACE_ID,
                        currentDocument.id()
                ).contentType("application/json").content(duplicateJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("同一批次不能重复审核同一文档标签"));

        // 查询当前文档标签，确认失败批次没有留下半批状态或审核历史
        List<DocumentTagResponse> currentTags = documentTagService.listDocumentTags(
                SPACE_ID,
                currentDocument.id()
        );
        assertThat(currentTags).singleElement().satisfies(response -> {
            assertThat(response.status()).isEqualTo("suggested");
            assertThat(response.reviews()).isEmpty();
        });
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM document_tag_reviews",
                Integer.class
        )).isZero();
    }

    @Test
    void rejectsRepeatedReviewAndRestoresSingleImmutableHistory() throws Exception {
        SourceDocument document = importDocument(
                "重复审核.md",
                "重复审核测试使用年会复盘原文。"
        );
        DocumentTag suggestion = saveSuggestion(
                "retrospective",
                "年会复盘",
                document,
                "年会复盘"
        );
        String acceptJson = "{\"reviews\":[{\"documentTagId\":\""
                + suggestion.id() + "\",\"action\":\"accept\"}]}";

        mockMvc.perform(post(
                        "/v1/spaces/{spaceId}/documents/{documentId}/tag-review-batches",
                        SPACE_ID,
                        document.id()
                ).contentType("application/json").content(acceptJson))
                .andExpect(status().isOk());

        String rejectJson = "{\"reviews\":[{\"documentTagId\":\""
                + suggestion.id() + "\",\"action\":\"reject\"}]}";
        mockMvc.perform(post(
                        "/v1/spaces/{spaceId}/documents/{documentId}/tag-review-batches",
                        SPACE_ID,
                        document.id()
                ).contentType("application/json").content(rejectJson))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(true))
                .andExpect(jsonPath("$.msg").value("文档标签已完成审核，不能重复操作"));

        String listJson = mockMvc.perform(get(
                        "/v1/spaces/{spaceId}/documents/{documentId}/tags",
                        SPACE_ID,
                        document.id()
                ))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        JsonNode restoredTag = objectMapper.readTree(listJson).path("data").get(0);
        assertThat(restoredTag.path("status").asText()).isEqualTo("confirmed");
        assertThat(restoredTag.path("reviews")).hasSize(1);
        assertThat(restoredTag.path("reviews").get(0).path("action").asText()).isEqualTo("accept");
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

        // 使用现有导入 Service 写入来源资料和内容指纹
        DocumentImportResponse response = documentService.importDocuments(SPACE_ID, List.of(file));

        // 查询完整来源资料用于标签证据逐字反查
        return sourceDocumentRepository.findById(
                SPACE_ID,
                response.results().getFirst().document().id()
        ).orElseThrow();
    }

    /**
     * 保存一条带逐字证据的 suggested AI 标签。
     *
     * @param suffix 测试标识后缀
     * @param name 标签展示名称
     * @param document 来源资料
     * @param quote 可逐字反查的原文
     * @return 已保存文档标签关系
     */
    private DocumentTag saveSuggestion(
            String suffix,
            String name,
            SourceDocument document,
            String quote
    ) {
        Instant createdAt = Instant.now();
        KnowledgeTag tag = new KnowledgeTag(
                TestIdFixtures.id("tag-" + suffix),
                SPACE_ID,
                name,
                null,
                "active",
                createdAt,
                createdAt
        );
        DocumentTag suggestion = new DocumentTag(
                TestIdFixtures.id("document-tag-" + suffix),
                SPACE_ID,
                document.id(),
                tag.id(),
                "ai",
                "suggested",
                0.9,
                TestIdFixtures.id("tag-run-" + suffix),
                document.contentHash(),
                PROMPT_VERSION,
                SCHEMA_VERSION,
                null,
                createdAt,
                createdAt
        );
        int startOffset = document.contentText().indexOf(quote);
        DocumentTagEvidence evidence = new DocumentTagEvidence(
                TestIdFixtures.id("tag-evidence-" + suffix),
                SPACE_ID,
                suggestion.id(),
                document.id(),
                "chunk-" + suffix,
                "标签审核测试",
                quote,
                startOffset,
                startOffset + quote.length(),
                createdAt
        );

        // 使用现有持久化领域服务写入 suggested 标签和已校验证据
        return persistenceService.saveAiSuggestion(tag, suggestion, List.of(evidence));
    }
}

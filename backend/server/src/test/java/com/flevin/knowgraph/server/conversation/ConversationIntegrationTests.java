package com.flevin.knowgraph.server.conversation;

import com.flevin.knowgraph.server.model.ai.rag.DocumentChunkFact;
import com.flevin.knowgraph.server.model.ai.rag.DocumentSectionFact;
import com.flevin.knowgraph.server.model.conversation.ConversationAnswerCitation;
import com.flevin.knowgraph.server.model.conversation.ConversationAnswerRequest;
import com.flevin.knowgraph.server.model.conversation.ConversationAnswerResult;
import com.flevin.knowgraph.server.model.conversation.ConversationDetailResponse;
import com.flevin.knowgraph.server.model.conversation.ConversationMessageResponse;
import com.flevin.knowgraph.server.model.conversation.CreateConversationRequest;
import com.flevin.knowgraph.server.model.document.DocumentImportResponse;
import com.flevin.knowgraph.server.model.document.SourceDocument;
import com.flevin.fixture.ConversationAnswerClientStub;
import com.flevin.fixture.ConversationAnswerFakeConfiguration;
import com.flevin.knowgraph.server.repository.document.DocumentChunkRepository;
import com.flevin.knowgraph.server.repository.document.DocumentSectionRepository;
import com.flevin.knowgraph.server.repository.document.SourceDocumentRepository;
import com.flevin.knowgraph.server.service.conversation.ConversationAnswerInvalidOutputException;
import com.flevin.knowgraph.server.service.document.DocumentService;
import com.flevin.knowgraph.server.support.TestIdFixtures;
import com.flevin.knowgraph.server.support.TestKnowledgeSpaceFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 有据问答会话接口集成测试：会话事实、空间隔离、引用逐字反查和证据状态。
 */
@SpringBootTest(properties = {
        "app.upload-dir=target/test-data/conversation-uploads",
        "ai.enabled=false"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(ConversationAnswerFakeConfiguration.class)
class ConversationIntegrationTests {

    private static final Long SPACE_ID = TestKnowledgeSpaceFixtures.DEFAULT_SPACE_ID;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ConversationAnswerClientStub answerClientStub;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private SourceDocumentRepository sourceDocumentRepository;

    @Autowired
    private DocumentSectionRepository documentSectionRepository;

    @Autowired
    private DocumentChunkRepository documentChunkRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearData() {
        // 按依赖顺序清理问答测试数据，保证每个用例从空态开始
        jdbcTemplate.update("DELETE FROM message_citations");
        jdbcTemplate.update("DELETE FROM conversation_messages");
        jdbcTemplate.update("DELETE FROM conversations");
        jdbcTemplate.update("DELETE FROM document_chunk_index_states");
        jdbcTemplate.update("DELETE FROM document_chunks");
        jdbcTemplate.update("DELETE FROM document_sections");
        jdbcTemplate.update("DELETE FROM source_documents");
        jdbcTemplate.update("DELETE FROM import_batches");
        TestKnowledgeSpaceFixtures.ensureDefaultSpace(jdbcTemplate);

        // 每个用例开始前清除上一个用例设置的 Fake 回答
        answerClientStub.nextResult.set(null);
        answerClientStub.nextFailure.set(null);
    }

    @Test
    void createsConversationAndRejectsForeignScopeDocument() throws Exception {
        SourceDocument document = importDocument("年会方案.md", "# 年会方案\n活动定在滨海厅举行。");

        // 正常创建：带标题和文档范围
        mockMvc.perform(post("/v1/spaces/{spaceId}/conversations", SPACE_ID)
                        .contentType("application/json")
                        .content("""
                                {"title":"年会答疑","scopeDocumentId":%d}
                                """.formatted(document.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conversationId").isNotEmpty())
                .andExpect(jsonPath("$.data.title").value("年会答疑"))
                .andExpect(jsonPath("$.data.scopeDocumentId").value(document.id()))
                .andExpect(jsonPath("$.data.status").value("active"));

        // 范围文档不存在：拒绝创建且不落库
        mockMvc.perform(post("/v1/spaces/{spaceId}/conversations", SPACE_ID)
                        .contentType("application/json")
                        .content("{\"scopeDocumentId\":987654321}"))
                .andExpect(status().isNotFound());
        assertThat(countConversations()).isEqualTo(1);
    }

    @Test
    void getConversationIsSpaceIsolated() throws Exception {
        Long conversationId = createConversation("空间隔离会话", null);

        // 其他空间查询同一会话必须 404，不得泄露任何消息
        mockMvc.perform(get("/v1/spaces/{spaceId}/conversations/{conversationId}",
                        TestIdFixtures.id("other-space"), conversationId))
                .andExpect(status().isNotFound());

        // 所属空间可正常恢复，新会话消息为空
        mockMvc.perform(get("/v1/spaces/{spaceId}/conversations/{conversationId}", SPACE_ID, conversationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conversation.conversationId").value(conversationId))
                .andExpect(jsonPath("$.data.messages.length()").value(0));
    }

    @Test
    void answersWithVerifiedCitationsAndGroundedStatus() throws Exception {
        SourceDocument document = importDocument("年会方案.md", "# 年会方案\n活动定在滨海厅举行。下午两点开始。");
        saveChunkFacts(document);
        Long conversationId = createConversation("引用会话", document.id());

        // Fake 返回两条合法引用：一条纯原文，一条带精确偏移
        answerClientStub.nextResult.set(new ConversationAnswerResult(
                "活动定在滨海厅举行，下午两点开始。",
                List.of(
                        new ConversationAnswerCitation("chunk-1", "活动定在滨海厅举行。", null, null),
                        new ConversationAnswerCitation("chunk-2", "下午两点开始。", 0, 7)
                )
        ));

        mockMvc.perform(post("/v1/spaces/{spaceId}/conversations/{conversationId}/messages",
                        SPACE_ID, conversationId)
                        .contentType("application/json")
                        .content("{\"question\":\"年会时间和地点？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("assistant"))
                .andExpect(jsonPath("$.data.status").value("completed"))
                .andExpect(jsonPath("$.data.groundingStatus").value("grounded"))
                .andExpect(jsonPath("$.data.citationCount").value(2))
                .andExpect(jsonPath("$.data.citationFailureCount").value(0))
                .andExpect(jsonPath("$.data.citations[0].sourceDocumentId").value(document.id()))
                .andExpect(jsonPath("$.data.citations[0].sectionPath").value("年会方案"))
                .andExpect(jsonPath("$.data.citations[0].sourceStale").value(false))
                .andExpect(jsonPath("$.data.citations[1].startOffset").value(0))
                .andExpect(jsonPath("$.data.citations[1].endOffset").value(7));

        // 历史包含用户问题和助手回答，用户消息无引用
        mockMvc.perform(get("/v1/spaces/{spaceId}/conversations/{conversationId}", SPACE_ID, conversationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages.length()").value(2))
                .andExpect(jsonPath("$.data.messages[0].role").value("user"))
                .andExpect(jsonPath("$.data.messages[0].citationCount").value(0))
                .andExpect(jsonPath("$.data.messages[1].groundingStatus").value("grounded"))
                .andExpect(jsonPath("$.data.messages[1].citations.length()").value(2));
    }

    @Test
    void removesInvalidQuoteAndMarksPartiallyGrounded() throws Exception {
        SourceDocument document = importDocument("年会方案.md", "# 年会方案\n活动定在滨海厅举行。下午两点开始。");
        saveChunkFacts(document);
        Long conversationId = createConversation("部分证据会话", document.id());

        // 一条合法引用 + 一条原文不在分片内的伪造引用
        answerClientStub.nextResult.set(new ConversationAnswerResult(
                "混合证据回答",
                List.of(
                        new ConversationAnswerCitation("chunk-1", "活动定在滨海厅举行。", null, null),
                        new ConversationAnswerCitation("chunk-1", "这句话不在分片原文里。", null, null)
                )
        ));

        mockMvc.perform(post("/v1/spaces/{spaceId}/conversations/{conversationId}/messages",
                        SPACE_ID, conversationId)
                        .contentType("application/json")
                        .content("{\"question\":\"时间和地点？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groundingStatus").value("partially_grounded"))
                .andExpect(jsonPath("$.data.citationCount").value(1))
                .andExpect(jsonPath("$.data.citationFailureCount").value(1))
                .andExpect(jsonPath("$.data.citations[0].quote").value("活动定在滨海厅举行。"));

        // 伪造引用不得落库
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM message_citations", Integer.class)).isEqualTo(1);
    }

    @Test
    void returnsInsufficientEvidenceWhenContextIsEmpty() throws Exception {
        Long conversationId = createConversation("无范围会话", null);

        // 即使客户端伪造引用，服务端上下文为空时也必须返回证据不足
        answerClientStub.nextResult.set(new ConversationAnswerResult(
                "我不知道",
                List.of(new ConversationAnswerCitation("chunk-1", "任意引用", null, null))
        ));

        mockMvc.perform(post("/v1/spaces/{spaceId}/conversations/{conversationId}/messages",
                        SPACE_ID, conversationId)
                        .contentType("application/json")
                        .content("{\"question\":\"随便问点什么\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groundingStatus").value("insufficient_evidence"))
                .andExpect(jsonPath("$.data.citationCount").value(0))
                .andExpect(jsonPath("$.data.citationFailureCount").value(1));

        // 伪造引用不得落库
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM message_citations", Integer.class)).isEqualTo(0);
    }

    @Test
    void recordsInvalidOutputSeparatelyAndKeepsUserMessage() throws Exception {
        Long conversationId = createConversation("非法输出会话", null);

        // 模拟生产适配器已区分出的结构化输出异常
        answerClientStub.nextFailure.set(new ConversationAnswerInvalidOutputException(
                "有据问答模型返回结构无法解析"
        ));

        mockMvc.perform(post("/v1/spaces/{spaceId}/conversations/{conversationId}/messages",
                        SPACE_ID, conversationId)
                        .contentType("application/json")
                        .content("{\"question\":\"年会地点在哪里？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("failed"))
                .andExpect(jsonPath("$.data.errorCategory").value("answer_invalid_output"))
                .andExpect(jsonPath("$.data.errorMessage").value("有据问答服务返回无效结果"))
                .andExpect(jsonPath("$.data.citationCount").value(0));

        // 非法模型输出不得生成引用，已经提交的用户问题仍作为会话事实保留
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM message_citations", Integer.class)).isEqualTo(0);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM conversation_messages WHERE role = 'user' AND status = 'completed'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void recordsModelFailureWithoutLosingUserMessage() throws Exception {
        Long conversationId = createConversation("模型异常会话", null);

        // 普通运行时异常代表模型连接、认证或供应商服务失败，不得误归类为非法结构
        answerClientStub.nextFailure.set(new IllegalStateException("测试模型不可用"));

        mockMvc.perform(post("/v1/spaces/{spaceId}/conversations/{conversationId}/messages",
                        SPACE_ID, conversationId)
                        .contentType("application/json")
                        .content("{\"question\":\"年会地点在哪里？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("failed"))
                .andExpect(jsonPath("$.data.errorCategory").value("answer_failed"))
                .andExpect(jsonPath("$.data.errorMessage").value("有据问答服务返回失败"))
                .andExpect(jsonPath("$.data.citationCount").value(0));

        // 模型失败不得产生引用，已经提交的用户问题仍可从会话历史恢复
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM message_citations", Integer.class)).isEqualTo(0);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM conversation_messages WHERE role = 'user' AND status = 'completed'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void marksCitationStaleWhenSourceDocumentChanges() throws Exception {
        SourceDocument document = importDocument("年会方案.md", "# 年会方案\n活动定在滨海厅举行。下午两点开始。");
        saveChunkFacts(document);
        Long conversationId = createConversation("来源失效会话", document.id());

        answerClientStub.nextResult.set(new ConversationAnswerResult(
                "活动定在滨海厅举行。",
                List.of(new ConversationAnswerCitation("chunk-1", "活动定在滨海厅举行。", null, null))
        ));
        mockMvc.perform(post("/v1/spaces/{spaceId}/conversations/{conversationId}/messages",
                        SPACE_ID, conversationId)
                        .contentType("application/json")
                        .content("{\"question\":\"地点？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groundingStatus").value("grounded"));

        // 模拟来源文档版本更新：内容指纹变化后历史引用读取侧标记 stale
        jdbcTemplate.update(
                "UPDATE source_documents SET content_hash = ? WHERE id = ?",
                "updated" + "0".repeat(57),
                document.id()
        );

        mockMvc.perform(get("/v1/spaces/{spaceId}/conversations/{conversationId}", SPACE_ID, conversationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages[1].citations[0].sourceStale").value(true))
                .andExpect(jsonPath("$.data.messages[1].citations[0].validationStatus").value("stale"));
    }

    @Test
    void rejectsInvalidQuestionAndForeignMessageAccess() throws Exception {
        Long conversationId = createConversation("校验会话", null);

        // 空问题被 Bean Validation 拒绝
        mockMvc.perform(post("/v1/spaces/{spaceId}/conversations/{conversationId}/messages",
                        SPACE_ID, conversationId)
                        .contentType("application/json")
                        .content("{\"question\":\"  \"}"))
                .andExpect(status().isBadRequest());

        // 用户消息不因失败回答丢失
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM conversation_messages", Integer.class)).isEqualTo(0);

        // 跨会话读取消息必须 404
        answerClientStub.nextResult.set(new ConversationAnswerResult("回答", List.of()));
        Long messageResponseId = submitMessageAndGetId(conversationId);
        Long otherConversationId = createConversation("其他会话", null);
        mockMvc.perform(get("/v1/spaces/{spaceId}/conversations/{conversationId}/messages/{messageId}",
                        SPACE_ID, otherConversationId, messageResponseId))
                .andExpect(status().isNotFound());
    }

    private Long submitMessageAndGetId(Long conversationId) throws Exception {
        String body = mockMvc.perform(post("/v1/spaces/{spaceId}/conversations/{conversationId}/messages",
                        SPACE_ID, conversationId)
                        .contentType("application/json")
                        .content("{\"question\":\"提问\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // 从统一响应 JSON 中提取 messageId
        com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(body);
        return node.get("data").get("messageId").asLong();
    }

    private Long createConversation(
            String title,
            Long scopeDocumentId
    ) throws Exception {
        String payload = scopeDocumentId == null
                ? "{\"title\":\"%s\"}".formatted(title)
                : "{\"title\":\"%s\",\"scopeDocumentId\":%d}".formatted(title, scopeDocumentId);
        String body = mockMvc.perform(post("/v1/spaces/{spaceId}/conversations", SPACE_ID)
                        .contentType("application/json")
                        .content(payload))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(body).get("data").get("conversationId").asLong();
    }

    private int countConversations() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM conversations", Integer.class);
    }

    /**
     * 为范围文档保存两个章节分片事实，供引用逐字反查使用。
     *
     * @param document 来源文档
     */
    private void saveChunkFacts(SourceDocument document) {
        Instant now = Instant.now();
        Long sectionRecordId = TestIdFixtures.id("conversation-section-1");
        documentSectionRepository.save(new DocumentSectionFact(
                sectionRecordId, SPACE_ID, document.id(), "section-1", "prd-markdown-section-v1",
                "年会方案", 1, "年会方案", 1,
                "活动定在滨海厅举行。下午两点开始。", 6, 30,
                "section-hash-conversation", now, now
        ));
        documentChunkRepository.save(new DocumentChunkFact(
                TestIdFixtures.id("conversation-chunk-1"), SPACE_ID, document.id(),
                sectionRecordId, "section-1", "chunk-1", "prd-markdown-section-v1", "年会方案",
                1, 1, "活动定在滨海厅举行。", 6, 16,
                "chunk-hash-conversation-1", "section-aware-v1", now, now
        ));
        documentChunkRepository.save(new DocumentChunkFact(
                TestIdFixtures.id("conversation-chunk-2"), SPACE_ID, document.id(),
                sectionRecordId, "section-1", "chunk-2", "prd-markdown-section-v1", "年会方案",
                2, 2, "下午两点开始。", 16, 23,
                "chunk-hash-conversation-2", "section-aware-v1", now, now
        ));
    }

    private SourceDocument importDocument(
            String name,
            String content
    ) {
        MockMultipartFile file = new MockMultipartFile(
                "files", name, "text/markdown", content.getBytes(StandardCharsets.UTF_8)
        );
        DocumentImportResponse response = documentService.importDocuments(SPACE_ID, List.of(file));
        return sourceDocumentRepository.findById(
                SPACE_ID, response.results().getFirst().document().id()
        ).orElseThrow();
    }
}

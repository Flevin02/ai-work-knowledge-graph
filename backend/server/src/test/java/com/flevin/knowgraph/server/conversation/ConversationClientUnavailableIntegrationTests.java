package com.flevin.knowgraph.server.conversation;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 问答客户端未接入时提交问题的降级边界测试。
 */
@SpringBootTest(properties = {
        "app.upload-dir=target/test-data/conversation-unavailable-uploads",
        "ai.enabled=false"
})
@AutoConfigureMockMvc
class ConversationClientUnavailableIntegrationTests {

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
        // 清理问答和来源资料数据，保证降级用例从空态开始
        jdbcTemplate.update("DELETE FROM message_citations");
        jdbcTemplate.update("DELETE FROM conversation_messages");
        jdbcTemplate.update("DELETE FROM conversations");
        jdbcTemplate.update("DELETE FROM document_chunk_index_states");
        jdbcTemplate.update("DELETE FROM document_chunks");
        jdbcTemplate.update("DELETE FROM document_sections");
        jdbcTemplate.update("DELETE FROM source_documents");
        jdbcTemplate.update("DELETE FROM import_batches");
        TestKnowledgeSpaceFixtures.ensureDefaultSpace(jdbcTemplate);
    }

    @Test
    void recordsFailedAnswerWhenClientUnavailable() throws Exception {
        // 导入文档并创建带范围的会话
        MockMultipartFile file = new MockMultipartFile(
                "files", "年会方案.md", "text/markdown",
                "# 年会方案\n活动定在滨海厅举行。".getBytes(StandardCharsets.UTF_8)
        );
        DocumentImportResponse response = documentService.importDocuments(SPACE_ID, List.of(file));
        SourceDocument document = sourceDocumentRepository.findById(
                SPACE_ID, response.results().getFirst().document().id()
        ).orElseThrow();
        String created = mockMvc.perform(post("/v1/spaces/{spaceId}/conversations", SPACE_ID)
                        .contentType("application/json")
                        .content("{\"title\":\"降级会话\",\"scopeDocumentId\":%d}".formatted(document.id())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Long conversationId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(created).get("data").get("conversationId").asLong();

        // 无生产客户端时提交问题：用户消息保留，回答记录为失败事实
        mockMvc.perform(post("/v1/spaces/{spaceId}/conversations/{conversationId}/messages",
                        SPACE_ID, conversationId)
                        .contentType("application/json")
                        .content("{\"question\":\"地点在哪里？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("assistant"))
                .andExpect(jsonPath("$.data.status").value("failed"))
                .andExpect(jsonPath("$.data.errorCategory").value("answer_client_unavailable"))
                .andExpect(jsonPath("$.data.errorMessage").value("有据问答服务未启用"));

        // 用户消息不丢失，历史可完整恢复
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM conversation_messages", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM conversation_messages WHERE role = 'user' AND status = 'completed'",
                Integer.class)).isEqualTo(1);
    }
}

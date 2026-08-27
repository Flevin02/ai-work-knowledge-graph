package com.flevin.knowgraph.server.association;

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
 * 文档关联运行、关系查询和审核 REST 契约集成测试。
 */
@SpringBootTest(properties = {
        "app.upload-dir=target/test-data/document-association-controller-uploads",
        "test.document-association-client=service"
})
@AutoConfigureMockMvc
@Import(DocumentAssociationServiceIntegrationTests.FakeAssociationConfiguration.class)
class DocumentAssociationControllerIntegrationTests {

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
        // 按外键依赖顺序清理本测试 HTTP 运行和关系数据
        jdbcTemplate.update("DELETE FROM document_relation_reviews");
        jdbcTemplate.update("DELETE FROM document_relation_evidences");
        jdbcTemplate.update("DELETE FROM document_relations");
        jdbcTemplate.update("DELETE FROM document_association_runs");
        jdbcTemplate.update("DELETE FROM source_documents");
        jdbcTemplate.update("DELETE FROM import_batches");
        TestKnowledgeSpaceFixtures.ensureDefaultSpace(jdbcTemplate);
    }

    @Test
    void createsRunListsRelationsAndReviewsByServerRelationId() throws Exception {
        SourceDocument source = importDocument(
                "会议纪要.md",
                "# 会议依据\n本次会议依据《年会方案.md》讨论场地。"
        );
        importDocument("年会方案.md", "# 方案\n年会方案记录场地和预算。");

        String runJson = mockMvc.perform(post(
                        "/v1/spaces/{spaceId}/documents/{documentId}/association-runs",
                        SPACE_ID,
                        source.id()
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value(false))
                .andExpect(jsonPath("$.data.status").value("completed"))
                .andExpect(jsonPath("$.data.tagCandidateCount").value(0))
                .andExpect(jsonPath("$.data.keywordCandidateCount").value(1))
                .andExpect(jsonPath("$.data.relations[0].status").value("suggested"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        JsonNode run = objectMapper.readTree(runJson).path("data");
        Long relationId = run.path("relations").get(0).path("id").asLong();

        mockMvc.perform(get(
                        "/v1/spaces/{spaceId}/documents/{documentId}/relations",
                        SPACE_ID,
                        source.id()
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(relationId))
                .andExpect(jsonPath("$.data[0].evidences.length()").value(2));

        mockMvc.perform(post(
                        "/v1/spaces/{spaceId}/documents/{documentId}/relation-review-batches",
                        SPACE_ID,
                        source.id()
                ).contentType("application/json")
                .content("{\"reviews\":[{\"relationId\":" + relationId
                        + ",\"action\":\"accept\",\"reason\":\"HTTP审核通过\"}]}") )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.acceptedCount").value(1))
                .andExpect(jsonPath("$.data.relations[0].status").value("confirmed"));
    }

    private SourceDocument importDocument(String name, String content) {
        MockMultipartFile file = new MockMultipartFile(
                "files",
                name,
                "text/plain",
                content.getBytes(StandardCharsets.UTF_8)
        );
        DocumentImportResponse response = documentService.importDocuments(SPACE_ID, List.of(file));
        return sourceDocumentRepository.findById(
                SPACE_ID,
                response.results().getFirst().document().id()
        ).orElseThrow();
    }
}

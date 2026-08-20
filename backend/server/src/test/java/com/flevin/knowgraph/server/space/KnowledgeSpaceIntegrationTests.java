package com.flevin.knowgraph.server.space;

import com.flevin.knowgraph.server.model.document.DocumentImportResponse;
import com.flevin.knowgraph.server.model.space.CreateKnowledgeSpaceRequest;
import com.flevin.knowgraph.server.model.space.KnowledgeSpaceResponse;
import com.flevin.knowgraph.server.service.document.DocumentService;
import com.flevin.knowgraph.server.service.space.KnowledgeSpaceService;
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
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.http.MediaType.APPLICATION_JSON;

@SpringBootTest(properties = {
        "app.database-path=target/test-data/knowledge-space.sqlite",
        "app.upload-dir=target/test-data/knowledge-space-uploads"
})
@AutoConfigureMockMvc
class KnowledgeSpaceIntegrationTests {

    private static final String DEFAULT_SPACE_ID = TestKnowledgeSpaceFixtures.DEFAULT_SPACE_ID;

    @Autowired
    private KnowledgeSpaceService knowledgeSpaceService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void resetSpacesAndDocuments() {
        // 按外键顺序删除测试数据库中的来源资料和导入批次
        jdbcTemplate.update("DELETE FROM source_documents");
        jdbcTemplate.update("DELETE FROM import_batches");

        // 清理除默认空间外的测试空间
        jdbcTemplate.update("DELETE FROM knowledge_spaces WHERE id <> ?", DEFAULT_SPACE_ID);

        // 为依赖固定标识的测试准备测试空间，生产 schema 不提供默认空间
        TestKnowledgeSpaceFixtures.ensureDefaultSpace(jdbcTemplate);
    }

    @Test
    void listsDefaultSpaceAndCreatesIndependentDirectory() throws Exception {
        // 查询启动脚本提供的默认知识空间
        List<KnowledgeSpaceResponse> initialSpaces = knowledgeSpaceService.listSpaces();

        assertThat(initialSpaces).extracting(KnowledgeSpaceResponse::id).contains(DEFAULT_SPACE_ID);

        CreateKnowledgeSpaceRequest request = new CreateKnowledgeSpaceRequest(
                "项目助理工作台",
                "用于验证空间隔离和本地目录。"
        );

        // 通过 Service 创建新知识空间和独立文件目录
        KnowledgeSpaceResponse createdSpace = knowledgeSpaceService.createSpace(request);

        assertThat(createdSpace.name()).isEqualTo("项目助理工作台");
        assertThat(Path.of("target/test-data/knowledge-space-uploads")
                .resolve(createdSpace.id())
                .resolve("documents"))
                .isDirectory();
    }

    @Test
    void importsDocumentsIntoSelectedSpaceAndSoftDeletesSpace() throws Exception {
        CreateKnowledgeSpaceRequest request = new CreateKnowledgeSpaceRequest(
                "空间隔离测试",
                ""
        );

        // 创建待测试的独立知识空间
        KnowledgeSpaceResponse createdSpace = knowledgeSpaceService.createSpace(request);

        MockMultipartFile document = new MockMultipartFile(
                "files",
                "空间资料.txt",
                "text/plain",
                "该资料只属于新建知识空间。".getBytes(StandardCharsets.UTF_8)
        );

        // 将来源资料写入新知识空间，而不是默认空间
        DocumentImportResponse importResponse = documentService.importDocuments(
                createdSpace.id(),
                List.of(document)
        );

        assertThat(importResponse.importedCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM source_documents WHERE space_id = ?",
                Integer.class,
                createdSpace.id()
        )).isEqualTo(1);

        // 软删除空间，保留来源资料和原始文件事实
        knowledgeSpaceService.deleteSpace(createdSpace.id());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM knowledge_spaces WHERE id = ?",
                String.class,
                createdSpace.id()
        )).isEqualTo("deleted");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM source_documents WHERE space_id = ?",
                Integer.class,
                createdSpace.id()
        )).isEqualTo(1);
    }

    @Test
    void controllerSupportsCreateListAndEmptySpaceState() throws Exception {
        // 通过 Controller 创建知识空间
        mockMvc.perform(post("/v1/spaces")
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"Controller 创建空间\",\"description\":\"用于接口验收\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value(false))
                .andExpect(jsonPath("$.data.name").value("Controller 创建空间"))
                .andExpect(jsonPath("$.data.status").value("active"));

        // 通过 Controller 查询有效知识空间列表
        mockMvc.perform(get("/v1/spaces"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));

        // 删除新建空间后，保留固定测试空间以验证正常软删除
        knowledgeSpaceService.deleteSpace(
                knowledgeSpaceService.listSpaces().stream()
                        .filter(space -> !DEFAULT_SPACE_ID.equals(space.id()))
                        .findFirst()
                        .orElseThrow()
                        .id()
        );

        // 删除最后一个有效空间后允许进入无空间空态
        mockMvc.perform(delete("/v1/spaces/" + DEFAULT_SPACE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value(false));

        mockMvc.perform(get("/v1/spaces"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }
}

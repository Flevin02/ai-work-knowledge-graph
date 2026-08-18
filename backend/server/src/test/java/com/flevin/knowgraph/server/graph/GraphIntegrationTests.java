package com.flevin.knowgraph.server.graph;

import com.flevin.knowgraph.server.model.document.DocumentImportResponse;
import com.flevin.knowgraph.server.model.graph.GraphEdge;
import com.flevin.knowgraph.server.model.graph.GraphEvidence;
import com.flevin.knowgraph.server.model.graph.GraphNode;
import com.flevin.knowgraph.server.model.space.CreateKnowledgeSpaceRequest;
import com.flevin.knowgraph.server.model.space.KnowledgeSpaceResponse;
import com.flevin.knowgraph.server.repository.graph.GraphRepository;
import com.flevin.knowgraph.server.service.document.DocumentService;
import com.flevin.knowgraph.server.service.space.KnowledgeSpaceService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.database-path=target/test-data/graph.sqlite",
        "app.upload-dir=target/test-data/graph-uploads"
})
@AutoConfigureMockMvc
class GraphIntegrationTests {

    private static final String DEFAULT_SPACE_ID = "default-space";
    private static final Instant TEST_TIME = Instant.parse("2026-08-17T09:00:00Z");

    @Autowired
    private GraphRepository graphRepository;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private KnowledgeSpaceService knowledgeSpaceService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void clearGraphData() {
        // 按外键依赖顺序清理审核、证据、关系、节点和来源资料
        jdbcTemplate.update("DELETE FROM review_actions");
        jdbcTemplate.update("DELETE FROM evidences");
        jdbcTemplate.update("DELETE FROM graph_edges");
        jdbcTemplate.update("DELETE FROM graph_nodes");
        jdbcTemplate.update("DELETE FROM source_documents");
        jdbcTemplate.update("DELETE FROM import_batches");

        // 清理上一次运行留下的非默认知识空间，避免固定测试数据库造成同名冲突
        jdbcTemplate.update("DELETE FROM knowledge_spaces WHERE id <> ?", DEFAULT_SPACE_ID);

        // 确保默认知识空间仍然可以承接本轮图谱测试数据
        jdbcTemplate.update(
                "UPDATE knowledge_spaces SET status = 'active' WHERE id = ?",
                DEFAULT_SPACE_ID
        );
    }

    @Test
    void createsAllStageTablesAndReturnsGraphWithEvidence() throws Exception {
        // 查询当前 SQLite 中的基础数据表名称
        Set<String> tableNames = Set.copyOf(jdbcTemplate.queryForList(
                "SELECT name FROM sqlite_master WHERE type = 'table'",
                String.class
        ));

        assertThat(tableNames).contains(
                "knowledge_spaces",
                "source_documents",
                "import_batches",
                "graph_nodes",
                "graph_edges",
                "evidences",
                "review_actions"
        );

        MockMultipartFile sourceFile = new MockMultipartFile(
                "files",
                "图谱证据.txt",
                "text/plain",
                "张三负责场地预订。".getBytes(StandardCharsets.UTF_8)
        );

        // 先导入来源资料，为关系证据提供真实外键目标
        DocumentImportResponse importResponse = documentService.importDocuments(
                DEFAULT_SPACE_ID,
                List.of(sourceFile)
        );
        String sourceDocumentId = importResponse.results().getFirst().document().id();

        GraphNode projectNode = new GraphNode(
                "node-project",
                DEFAULT_SPACE_ID,
                "project",
                "2026 年公司年会",
                "年会项目",
                "active",
                "project:annual-party",
                List.of(sourceDocumentId),
                TEST_TIME,
                TEST_TIME
        );
        GraphNode personNode = new GraphNode(
                "node-person",
                DEFAULT_SPACE_ID,
                "person",
                "张三",
                "年会项目负责人",
                "active",
                "person:zhang-san",
                List.of(sourceDocumentId),
                TEST_TIME,
                TEST_TIME
        );

        // 保存两个节点，验证图谱节点 Repository 写入
        graphRepository.saveNode(projectNode);
        graphRepository.saveNode(personNode);

        GraphEdge confirmedEdge = new GraphEdge(
                "edge-person-project",
                DEFAULT_SPACE_ID,
                personNode.id(),
                projectNode.id(),
                "项目负责人",
                "confirmed",
                0.96,
                TEST_TIME,
                TEST_TIME
        );
        GraphEdge suggestedEdge = new GraphEdge(
                "edge-project-review",
                DEFAULT_SPACE_ID,
                projectNode.id(),
                personNode.id(),
                "待核对关系",
                "suggested",
                0.62,
                TEST_TIME,
                TEST_TIME
        );

        // 保存已确认和待审核关系，验证状态统计边界
        graphRepository.saveEdge(confirmedEdge);
        graphRepository.saveEdge(suggestedEdge);

        // 保存已确认关系的来源证据
        graphRepository.saveEvidence(new GraphEvidence(
                "evidence-person-project",
                DEFAULT_SPACE_ID,
                confirmedEdge.id(),
                sourceDocumentId,
                "图谱证据.txt",
                "张三负责场地预订。",
                "第 1 行",
                "rule",
                TEST_TIME
        ));

        // 用 SQL 写入审核记录，确认审核表外键和结构可以承接下一阶段
        jdbcTemplate.update(
                """
                        INSERT INTO review_actions (
                            id, space_id, edge_id, action, reason, operator_name, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                "review-1",
                DEFAULT_SPACE_ID,
                suggestedEdge.id(),
                "reject",
                "证据不足",
                "local-user",
                TEST_TIME.toString()
        );

        // 查询图谱摘要，验证节点、已确认关系和待审核关系来自 SQLite 真实统计
        mockMvc.perform(get("/v1/spaces/{spaceId}/graph/summary", DEFAULT_SPACE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodes").value(2))
                .andExpect(jsonPath("$.data.edges").value(1))
                .andExpect(jsonPath("$.data.pendingReviews").value(1));

        // 查询图谱节点、关系和证据，验证批量关联结果
        mockMvc.perform(get("/v1/spaces/{spaceId}/graph", DEFAULT_SPACE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodes.length()").value(2))
                .andExpect(jsonPath("$.data.edges.length()").value(2))
                .andExpect(jsonPath("$.data.edges[0].evidence.length()").value(1))
                .andExpect(jsonPath("$.data.edges[0].evidence[0].sourceDocumentName").value("图谱证据.txt"));
    }

    @Test
    void graphSummaryIsIsolatedByKnowledgeSpace() {
        // 创建第二个知识空间，验证空间查询不会混入默认空间数据
        KnowledgeSpaceResponse secondSpace = knowledgeSpaceService.createSpace(
                new CreateKnowledgeSpaceRequest("第二个空间", "隔离测试")
        );

        // 在默认空间写入一个节点
        graphRepository.saveNode(new GraphNode(
                "default-only-node",
                DEFAULT_SPACE_ID,
                "project",
                "默认空间节点",
                "只属于默认空间",
                "active",
                "project:default-only",
                List.of(),
                TEST_TIME,
                TEST_TIME
        ));

        assertThat(graphRepository.countNodes(DEFAULT_SPACE_ID)).isEqualTo(1);
        assertThat(graphRepository.countNodes(secondSpace.id())).isZero();
    }
}

package com.flevin.knowgraph.server.repository.graph;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flevin.knowgraph.server.model.graph.GraphEdge;
import com.flevin.knowgraph.server.model.graph.GraphEvidence;
import com.flevin.knowgraph.server.model.graph.GraphNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

/**
 * 图谱数据访问对象，负责节点、关系、证据的批量查询和基础写入。
 */
@Repository
public class GraphRepository {

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private static final String FIND_NODES_SQL = """
            SELECT id, space_id, node_type, label, summary, status, normalized_key,
                   source_ids_json, created_at, updated_at
            FROM graph_nodes
            WHERE space_id = ? AND status != 'stale'
            ORDER BY created_at ASC, id ASC
            """;

    private static final String FIND_EDGES_SQL = """
            SELECT id, space_id, source_node_id, target_node_id, relation_type,
                   status, confidence, created_at, updated_at
            FROM graph_edges
            WHERE space_id = ? AND status != 'stale'
            ORDER BY created_at ASC, id ASC
            """;

    private static final String FIND_EVIDENCES_SQL = """
            SELECT e.id, e.space_id, e.edge_id, e.source_document_id,
                   d.name AS source_document_name, e.quote, e.locator,
                   e.extraction_method, e.created_at
            FROM evidences e
            INNER JOIN source_documents d ON d.id = e.source_document_id
            WHERE e.space_id = :spaceId AND e.edge_id IN (:edgeIds)
            ORDER BY e.created_at ASC, e.id ASC
            """;

    private static final String INSERT_NODE_SQL = """
            INSERT INTO graph_nodes (
                id, space_id, node_type, label, summary, status, normalized_key,
                source_ids_json, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_EDGE_SQL = """
            INSERT INTO graph_edges (
                id, space_id, source_node_id, target_node_id, relation_type,
                status, confidence, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_EVIDENCE_SQL = """
            INSERT INTO evidences (
                id, space_id, edge_id, source_document_id, quote, locator,
                extraction_method, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final ObjectMapper objectMapper;

    public GraphRepository(
            JdbcTemplate jdbcTemplate,
            NamedParameterJdbcTemplate namedParameterJdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询指定知识空间的全部有效图谱节点。
     *
     * @param spaceId 知识空间标识
     * @return 图谱节点列表
     */
    public List<GraphNode> findNodes(String spaceId) {
        // 批量查询指定知识空间的有效节点
        return jdbcTemplate.query(FIND_NODES_SQL, this::mapNode, spaceId);
    }

    /**
     * 查询指定知识空间的全部未失效图谱关系。
     *
     * @param spaceId 知识空间标识
     * @return 图谱关系列表
     */
    public List<GraphEdge> findEdges(String spaceId) {
        // 批量查询指定知识空间的未失效关系
        return jdbcTemplate.query(FIND_EDGES_SQL, this::mapEdge, spaceId);
    }

    /**
     * 批量查询一组关系对应的证据，避免逐关系访问数据库。
     *
     * @param spaceId 知识空间标识
     * @param edgeIds 关系标识列表
     * @return 关系证据列表
     */
    public List<GraphEvidence> findEvidencesByEdgeIds(
            String spaceId,
            List<String> edgeIds
    ) {
        if (edgeIds.isEmpty()) {
            return List.of();
        }

        // 绑定知识空间和关系标识集合，批量查询全部证据
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("spaceId", spaceId)
                .addValue("edgeIds", edgeIds);
        return namedParameterJdbcTemplate.query(FIND_EVIDENCES_SQL, parameters, this::mapEvidence);
    }

    /**
     * 统计指定知识空间的有效节点数量。
     *
     * @param spaceId 知识空间标识
     * @return 有效节点数量
     */
    public int countNodes(String spaceId) {
        // 统计未失效图谱节点数量
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM graph_nodes WHERE space_id = ? AND status != 'stale'",
                Integer.class,
                spaceId
        );
        return count == null ? 0 : count;
    }

    /**
     * 统计指定知识空间的已确认关系数量。
     *
     * @param spaceId 知识空间标识
     * @return 已确认关系数量
     */
    public int countConfirmedEdges(String spaceId) {
        // 统计正式进入图谱的已确认关系数量
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM graph_edges WHERE space_id = ? AND status = 'confirmed'",
                Integer.class,
                spaceId
        );
        return count == null ? 0 : count;
    }

    /**
     * 统计指定知识空间的待审核关系数量。
     *
     * @param spaceId 知识空间标识
     * @return 待审核关系数量
     */
    public int countPendingEdges(String spaceId) {
        // 统计 AI 或规则生成但尚未人工确认的关系数量
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM graph_edges WHERE space_id = ? AND status = 'suggested'",
                Integer.class,
                spaceId
        );
        return count == null ? 0 : count;
    }

    /**
     * 保存图谱节点，为后续 AI 抽取和人工录入提供统一写入入口。
     *
     * @param node 图谱节点
     */
    public void saveNode(GraphNode node) {
        // 将节点来源资料标识序列化为 JSON 数组
        String sourceIdsJson = writeSourceIds(node.sourceIds());

        // 保存节点结构、状态、来源和时间字段
        jdbcTemplate.update(
                INSERT_NODE_SQL,
                node.id(),
                node.spaceId(),
                node.type(),
                node.label(),
                node.summary(),
                node.status(),
                node.normalizedKey(),
                sourceIdsJson,
                node.createdAt().toString(),
                node.updatedAt().toString()
        );
    }

    /**
     * 保存图谱关系。
     *
     * @param edge 图谱关系
     */
    public void saveEdge(GraphEdge edge) {
        // 保存关系主体、客体、类型、状态和置信度
        jdbcTemplate.update(
                INSERT_EDGE_SQL,
                edge.id(),
                edge.spaceId(),
                edge.sourceNodeId(),
                edge.targetNodeId(),
                edge.type(),
                edge.status(),
                edge.confidence(),
                edge.createdAt().toString(),
                edge.updatedAt().toString()
        );
    }

    /**
     * 保存图谱关系证据。
     *
     * @param evidence 图谱关系证据
     */
    public void saveEvidence(GraphEvidence evidence) {
        // 保存证据原文、定位和来源资料关联
        jdbcTemplate.update(
                INSERT_EVIDENCE_SQL,
                evidence.id(),
                evidence.spaceId(),
                evidence.edgeId(),
                evidence.sourceDocumentId(),
                evidence.quote(),
                evidence.locator(),
                evidence.extractionMethod(),
                evidence.createdAt().toString()
        );
    }

    /**
     * 将节点来源资料标识转换为数据库 JSON 字符串。
     *
     * @param sourceIds 来源资料标识
     * @return JSON 数组字符串
     */
    private String writeSourceIds(List<String> sourceIds) {
        try {
            // 使用项目统一 ObjectMapper 序列化来源资料标识
            return objectMapper.writeValueAsString(sourceIds);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化图谱节点来源资料标识", exception);
        }
    }

    /**
     * 将 JDBC 查询结果映射为图谱节点。
     *
     * @param resultSet JDBC 查询结果
     * @param rowNumber 当前结果行号
     * @return 图谱节点
     * @throws SQLException 字段或来源 JSON 读取失败时抛出
     */
    private GraphNode mapNode(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        try {
            // 解析节点来源资料标识 JSON 数组
            List<String> sourceIds = objectMapper.readValue(
                    resultSet.getString("source_ids_json"),
                    STRING_LIST_TYPE
            );
            return new GraphNode(
                    resultSet.getString("id"),
                    resultSet.getString("space_id"),
                    resultSet.getString("node_type"),
                    resultSet.getString("label"),
                    resultSet.getString("summary"),
                    resultSet.getString("status"),
                    resultSet.getString("normalized_key"),
                    sourceIds,
                    Instant.parse(resultSet.getString("created_at")),
                    Instant.parse(resultSet.getString("updated_at"))
            );
        } catch (JsonProcessingException exception) {
            throw new SQLException("图谱节点来源资料标识不是有效 JSON", exception);
        }
    }

    /**
     * 将 JDBC 查询结果映射为图谱关系。
     *
     * @param resultSet JDBC 查询结果
     * @param rowNumber 当前结果行号
     * @return 图谱关系
     * @throws SQLException 字段读取失败时抛出
     */
    private GraphEdge mapEdge(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        // 从数据库字段恢复图谱关系和 ISO-8601 时间
        return new GraphEdge(
                resultSet.getString("id"),
                resultSet.getString("space_id"),
                resultSet.getString("source_node_id"),
                resultSet.getString("target_node_id"),
                resultSet.getString("relation_type"),
                resultSet.getString("status"),
                resultSet.getDouble("confidence"),
                Instant.parse(resultSet.getString("created_at")),
                Instant.parse(resultSet.getString("updated_at"))
        );
    }

    /**
     * 将 JDBC 查询结果映射为图谱关系证据。
     *
     * @param resultSet JDBC 查询结果
     * @param rowNumber 当前结果行号
     * @return 图谱关系证据
     * @throws SQLException 字段读取失败时抛出
     */
    private GraphEvidence mapEvidence(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        // 从证据和来源资料联合查询结果恢复可追溯证据模型
        return new GraphEvidence(
                resultSet.getString("id"),
                resultSet.getString("space_id"),
                resultSet.getString("edge_id"),
                resultSet.getString("source_document_id"),
                resultSet.getString("source_document_name"),
                resultSet.getString("quote"),
                resultSet.getString("locator"),
                resultSet.getString("extraction_method"),
                Instant.parse(resultSet.getString("created_at"))
        );
    }
}

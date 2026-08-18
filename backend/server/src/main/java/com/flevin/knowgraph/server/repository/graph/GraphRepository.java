package com.flevin.knowgraph.server.repository.graph;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flevin.knowgraph.server.model.graph.GraphEdge;
import com.flevin.knowgraph.server.model.graph.GraphEvidence;
import com.flevin.knowgraph.server.model.graph.GraphNode;
import com.flevin.knowgraph.server.repository.entity.GraphEdgeEntity;
import com.flevin.knowgraph.server.repository.entity.GraphEvidenceEntity;
import com.flevin.knowgraph.server.repository.entity.GraphEvidenceRow;
import com.flevin.knowgraph.server.repository.entity.GraphNodeEntity;
import com.flevin.knowgraph.server.repository.mapper.GraphEdgeMapper;
import com.flevin.knowgraph.server.repository.mapper.GraphEvidenceMapper;
import com.flevin.knowgraph.server.repository.mapper.GraphNodeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 图谱数据访问对象，统一使用 MyBatis-Plus Mapper 完成节点、关系、证据查询和写入。
 */
@Repository
@RequiredArgsConstructor
public class GraphRepository {

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private final GraphNodeMapper graphNodeMapper;
    private final GraphEdgeMapper graphEdgeMapper;
    private final GraphEvidenceMapper graphEvidenceMapper;
    private final ObjectMapper objectMapper;

    /**
     * 查询指定知识空间的全部有效图谱节点。
     *
     * @param spaceId 知识空间标识
     * @return 图谱节点列表
     */
    public List<GraphNode> findNodes(String spaceId) {
        // 通过专用 Mapper 查询未失效节点，复杂排序仍由 Mapper SQL 保持明确
        return graphNodeMapper.findActiveBySpaceId(spaceId).stream()
                .map(this::toDomain)
                .toList();
    }

    /**
     * 查询指定知识空间的全部未失效图谱关系。
     *
     * @param spaceId 知识空间标识
     * @return 图谱关系列表
     */
    public List<GraphEdge> findEdges(String spaceId) {
        // 通过专用 Mapper 查询未失效关系，保持原有创建顺序
        return graphEdgeMapper.findActiveBySpaceId(spaceId).stream()
                .map(this::toDomain)
                .toList();
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

        // 通过 Mapper 的动态 IN 查询批量读取证据和来源资料名称
        return graphEvidenceMapper.findRowsBySpaceIdAndEdgeIds(spaceId, edgeIds).stream()
                .map(this::toDomain)
                .toList();
    }

    /**
     * 统计指定知识空间的有效节点数量。
     *
     * @param spaceId 知识空间标识
     * @return 有效节点数量
     */
    public int countNodes(String spaceId) {
        // 使用 MyBatis-Plus Lambda 条件构造器统计未失效节点
        Long count = graphNodeMapper.selectCount(
                Wrappers.<GraphNodeEntity>lambdaQuery()
                        .eq(GraphNodeEntity::getSpaceId, spaceId)
                        .ne(GraphNodeEntity::getStatus, "stale")
        );
        return count == null ? 0 : Math.toIntExact(count);
    }

    /**
     * 统计指定知识空间的已确认关系数量。
     *
     * @param spaceId 知识空间标识
     * @return 已确认关系数量
     */
    public int countConfirmedEdges(String spaceId) {
        // 使用 MyBatis-Plus Lambda 条件构造器统计已确认关系
        Long count = graphEdgeMapper.selectCount(
                Wrappers.<GraphEdgeEntity>lambdaQuery()
                        .eq(GraphEdgeEntity::getSpaceId, spaceId)
                        .eq(GraphEdgeEntity::getStatus, "confirmed")
        );
        return count == null ? 0 : Math.toIntExact(count);
    }

    /**
     * 统计指定知识空间的待审核关系数量。
     *
     * @param spaceId 知识空间标识
     * @return 待审核关系数量
     */
    public int countPendingEdges(String spaceId) {
        // 使用 MyBatis-Plus Lambda 条件构造器统计待审核关系
        Long count = graphEdgeMapper.selectCount(
                Wrappers.<GraphEdgeEntity>lambdaQuery()
                        .eq(GraphEdgeEntity::getSpaceId, spaceId)
                        .eq(GraphEdgeEntity::getStatus, "suggested")
        );
        return count == null ? 0 : Math.toIntExact(count);
    }

    /**
     * 保存图谱节点，为后续 AI 抽取和人工录入提供统一写入入口。
     *
     * @param node 图谱节点
     */
    public void saveNode(GraphNode node) {
        // 将节点来源资料标识序列化为 JSON 并转换为持久化实体
        GraphNodeEntity entity = toEntity(node);

        // 使用 BaseMapper 保存图谱节点
        graphNodeMapper.insert(entity);
    }

    /**
     * 保存图谱关系。
     *
     * @param edge 图谱关系
     */
    public void saveEdge(GraphEdge edge) {
        // 将领域关系转换为 MyBatis-Plus 持久化实体
        GraphEdgeEntity entity = toEntity(edge);

        // 使用 BaseMapper 保存图谱关系
        graphEdgeMapper.insert(entity);
    }

    /**
     * 保存图谱关系证据。
     *
     * @param evidence 图谱关系证据
     */
    public void saveEvidence(GraphEvidence evidence) {
        // 将领域证据转换为 MyBatis-Plus 持久化实体
        GraphEvidenceEntity entity = toEntity(evidence);

        // 使用 BaseMapper 保存图谱关系证据
        graphEvidenceMapper.insert(entity);
    }

    /**
     * 删除来源资料后更新图谱来源贡献，并失效无剩余来源的节点和关系。
     *
     * @param spaceId 知识空间标识
     * @param documentId 已删除来源资料标识
     * @param updatedAt 更新时间
     */
    public void invalidateBySourceDocument(
            String spaceId,
            String documentId,
            Instant updatedAt
    ) {
        // 查询当前知识空间全部未失效节点，逐个检查来源资料贡献
        List<GraphNodeEntity> nodes = graphNodeMapper.findActiveBySpaceId(spaceId);
        List<String> staleNodeIds = new ArrayList<>();

        nodes.forEach(node -> invalidateNodeSource(node, documentId, updatedAt, staleNodeIds));

        if (!staleNodeIds.isEmpty()) {
            // 将连接到失效节点的关系同步标记为 stale
            graphEdgeMapper.markStaleByNodeIds(
                    spaceId,
                    staleNodeIds,
                    updatedAt.toString()
            );
        }

        // 将全部有效证据都来自已删除资料的关系标记为 stale
        graphEdgeMapper.markStaleWithoutActiveEvidence(
                spaceId,
                documentId,
                updatedAt.toString()
        );
    }

    /**
     * 从节点来源列表移除已删除文档，并在无剩余来源时失效节点。
     *
     * @param node 节点持久化实体
     * @param documentId 已删除来源资料标识
     * @param updatedAt 更新时间
     * @param staleNodeIds 收集已失效节点标识
     */
    private void invalidateNodeSource(
            GraphNodeEntity node,
            String documentId,
            Instant updatedAt,
            List<String> staleNodeIds
    ) {
        List<String> sourceIds = readSourceIds(node.getSourceIdsJson());
        if (!sourceIds.contains(documentId)) {
            return;
        }

        // 移除当前来源资料，保留其他文档对同一节点的支撑
        List<String> remainingSourceIds = sourceIds.stream()
                .filter(sourceId -> !documentId.equals(sourceId))
                .toList();
        node.setSourceIdsJson(writeSourceIds(remainingSourceIds));
        node.setUpdatedAt(updatedAt.toString());

        if (remainingSourceIds.isEmpty()) {
            node.setStatus("stale");
            staleNodeIds.add(node.getId());
        }

        // 使用 MyBatis-Plus 按主键更新节点来源和状态
        graphNodeMapper.updateById(node);
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
     * 将数据库 JSON 字符串解析为节点来源资料标识。
     *
     * @param sourceIdsJson 来源资料标识 JSON
     * @return 来源资料标识列表
     */
    private List<String> readSourceIds(String sourceIdsJson) {
        try {
            // 使用项目统一 ObjectMapper 解析来源资料标识
            return objectMapper.readValue(sourceIdsJson, STRING_LIST_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("图谱节点来源资料标识不是有效 JSON", exception);
        }
    }

    /**
     * 将 MyBatis-Plus 节点实体转换为领域模型。
     *
     * @param entity 节点持久化实体
     * @return 图谱节点领域模型
     */
    private GraphNode toDomain(GraphNodeEntity entity) {
        try {
            // 解析节点来源资料标识 JSON 数组
            List<String> sourceIds = readSourceIds(entity.getSourceIdsJson());
            return new GraphNode(
                    entity.getId(),
                    entity.getSpaceId(),
                    entity.getNodeType(),
                    entity.getLabel(),
                    entity.getSummary(),
                    entity.getStatus(),
                    entity.getNormalizedKey(),
                    sourceIds,
                    Instant.parse(entity.getCreatedAt()),
                    Instant.parse(entity.getUpdatedAt())
            );
        } catch (IllegalStateException exception) {
            throw exception;
        }
    }

    /**
     * 将 MyBatis-Plus 关系实体转换为领域模型。
     *
     * @param entity 关系持久化实体
     * @return 图谱关系领域模型
     */
    private GraphEdge toDomain(GraphEdgeEntity entity) {
        return new GraphEdge(
                entity.getId(),
                entity.getSpaceId(),
                entity.getSourceNodeId(),
                entity.getTargetNodeId(),
                entity.getRelationType(),
                entity.getStatus(),
                entity.getConfidence(),
                Instant.parse(entity.getCreatedAt()),
                Instant.parse(entity.getUpdatedAt())
        );
    }

    /**
     * 将联合查询证据行转换为领域模型。
     *
     * @param row 证据联合查询行
     * @return 图谱证据领域模型
     */
    private GraphEvidence toDomain(GraphEvidenceRow row) {
        return new GraphEvidence(
                row.getId(),
                row.getSpaceId(),
                row.getEdgeId(),
                row.getSourceDocumentId(),
                row.getSourceDocumentName(),
                row.getQuote(),
                row.getLocator(),
                row.getExtractionMethod(),
                Instant.parse(row.getCreatedAt())
        );
    }

    /**
     * 将图谱节点领域模型转换为持久化实体。
     *
     * @param node 图谱节点
     * @return 节点持久化实体
     */
    private GraphNodeEntity toEntity(GraphNode node) {
        GraphNodeEntity entity = new GraphNodeEntity();
        entity.setId(node.id());
        entity.setSpaceId(node.spaceId());
        entity.setNodeType(node.type());
        entity.setLabel(node.label());
        entity.setSummary(node.summary());
        entity.setStatus(node.status());
        entity.setNormalizedKey(node.normalizedKey());
        entity.setSourceIdsJson(writeSourceIds(node.sourceIds()));
        entity.setCreatedAt(node.createdAt().toString());
        entity.setUpdatedAt(node.updatedAt().toString());
        return entity;
    }

    /**
     * 将图谱关系领域模型转换为持久化实体。
     *
     * @param edge 图谱关系
     * @return 关系持久化实体
     */
    private GraphEdgeEntity toEntity(GraphEdge edge) {
        GraphEdgeEntity entity = new GraphEdgeEntity();
        entity.setId(edge.id());
        entity.setSpaceId(edge.spaceId());
        entity.setSourceNodeId(edge.sourceNodeId());
        entity.setTargetNodeId(edge.targetNodeId());
        entity.setRelationType(edge.type());
        entity.setStatus(edge.status());
        entity.setConfidence(edge.confidence());
        entity.setCreatedAt(edge.createdAt().toString());
        entity.setUpdatedAt(edge.updatedAt().toString());
        return entity;
    }

    /**
     * 将图谱证据领域模型转换为持久化实体。
     *
     * @param evidence 图谱证据
     * @return 证据持久化实体
     */
    private GraphEvidenceEntity toEntity(GraphEvidence evidence) {
        GraphEvidenceEntity entity = new GraphEvidenceEntity();
        entity.setId(evidence.id());
        entity.setSpaceId(evidence.spaceId());
        entity.setEdgeId(evidence.edgeId());
        entity.setSourceDocumentId(evidence.sourceDocumentId());
        entity.setQuote(evidence.quote());
        entity.setLocator(evidence.locator());
        entity.setExtractionMethod(evidence.extractionMethod());
        entity.setCreatedAt(evidence.createdAt().toString());
        return entity;
    }
}

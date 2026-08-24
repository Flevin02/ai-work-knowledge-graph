package com.flevin.knowgraph.server.repository.graph;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.flevin.knowgraph.server.model.graph.GraphEdge;
import com.flevin.knowgraph.server.model.graph.GraphEvidence;
import com.flevin.knowgraph.server.model.graph.GraphNode;
import com.flevin.knowgraph.server.repository.entity.GraphEdgeEntity;
import com.flevin.knowgraph.server.repository.entity.GraphEvidenceEntity;
import com.flevin.knowgraph.server.repository.entity.GraphNodeEntity;
import com.flevin.knowgraph.server.repository.mapper.GraphEdgeMapper;
import com.flevin.knowgraph.server.repository.mapper.GraphEvidenceMapper;
import com.flevin.knowgraph.server.repository.mapper.GraphNodeMapper;
import com.flevin.knowgraph.server.repository.mapping.GraphEntityMapper;
import com.flevin.knowgraph.server.repository.mapping.PersistenceMappingSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 图谱数据访问对象，统一使用 MyBatis-Plus Mapper 完成节点、关系、证据查询和写入。
 */
@Repository
@RequiredArgsConstructor
public class GraphRepository {

    private final GraphNodeMapper graphNodeMapper;
    private final GraphEdgeMapper graphEdgeMapper;
    private final GraphEvidenceMapper graphEvidenceMapper;
    private final GraphEntityMapper entityMapper;
    private final PersistenceMappingSupport mappingSupport;

    /**
     * 查询指定知识空间的全部有效图谱节点。
     *
     * @param spaceId 知识空间标识
     * @return 图谱节点列表
     */
    public List<GraphNode> findNodes(String spaceId) {
        // 通过专用 Mapper 查询未失效节点，复杂排序仍由 Mapper SQL 保持明确
        return graphNodeMapper.findActiveBySpaceId(spaceId).stream()
                .map(entityMapper::toDomain)
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
                .map(entityMapper::toDomain)
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
                .map(entityMapper::toDomain)
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
        GraphNodeEntity entity = entityMapper.toEntity(node);

        // 使用 BaseMapper 保存图谱节点
        graphNodeMapper.insert(entity);
    }

    /**
     * 批量查询指定规范化键对应的有效图谱节点。
     *
     * @param spaceId 知识空间标识
     * @param normalizedKeys 规范化实体键
     * @return 已存在的图谱节点
     */
    public List<GraphNode> findNodesByNormalizedKeys(
            String spaceId,
            List<String> normalizedKeys
    ) {
        if (normalizedKeys.isEmpty()) {
            return List.of();
        }

        // 批量查询候选实体对应节点，避免按实体逐条查询数据库
        return graphNodeMapper.findBySpaceIdAndNormalizedKeys(spaceId, normalizedKeys).stream()
                .map(entityMapper::toDomain)
                .toList();
    }

    /**
     * 更新已有图谱节点，保留节点标识和创建时间。
     *
     * @param node 已组装的节点
     */
    public void updateNode(GraphNode node) {
        // 将节点来源和摘要更新回同一条图谱事实
        graphNodeMapper.updateById(entityMapper.toEntity(node));
    }

    /**
     * 保存图谱关系。
     *
     * @param edge 图谱关系
     */
    public void saveEdge(GraphEdge edge) {
        // 将领域关系转换为 MyBatis-Plus 持久化实体
        GraphEdgeEntity entity = entityMapper.toEntity(edge);

        // 使用 BaseMapper 保存图谱关系
        graphEdgeMapper.insert(entity);
    }

    /**
     * 查询指定关系。
     *
     * @param edgeId 关系标识
     * @return 关系；不存在时为空
     */
    public Optional<GraphEdge> findEdge(String edgeId) {
        return graphEdgeMapper.findById(edgeId).map(entityMapper::toDomain);
    }

    /**
     * 按空间、主体、客体和关系类型查询候选关系，避免审核端信任客户端关系标识。
     *
     * @param spaceId 知识空间标识
     * @param sourceNodeId 主体节点标识
     * @param targetNodeId 客体节点标识
     * @param relationType 关系类型
     * @return 关系；不存在时为空
     */
    public Optional<GraphEdge> findEdgeBySignature(
            String spaceId,
            String sourceNodeId,
            String targetNodeId,
            String relationType
    ) {
        return Optional.ofNullable(graphEdgeMapper.selectOne(
                Wrappers.<GraphEdgeEntity>lambdaQuery()
                        .eq(GraphEdgeEntity::getSpaceId, spaceId)
                        .eq(GraphEdgeEntity::getSourceNodeId, sourceNodeId)
                        .eq(GraphEdgeEntity::getTargetNodeId, targetNodeId)
                        .eq(GraphEdgeEntity::getRelationType, relationType)
                        .orderByDesc(GraphEdgeEntity::getUpdatedAt)
                        .last("LIMIT 1")
        )).map(entityMapper::toDomain);
    }

    /**
     * 更新关系审核状态和更新时间。
     *
     * @param edgeId 关系标识
     * @param status 新关系状态
     * @param updatedAt 更新时间
     */
    public void updateEdgeStatus(
            String edgeId,
            String status,
            Instant updatedAt
    ) {
        GraphEdgeEntity entity = graphEdgeMapper.selectById(edgeId);
        if (entity == null) {
            return;
        }
        entity.setStatus(status);
        entity.setUpdatedAt(updatedAt.toString());

        // 仅更新关系审核状态，保留主体、客体、证据和创建时间
        graphEdgeMapper.updateById(entity);
    }

    /**
     * 保存图谱关系证据。
     *
     * @param evidence 图谱关系证据
     */
    public void saveEvidence(GraphEvidence evidence) {
        // 将领域证据转换为 MyBatis-Plus 持久化实体
        GraphEvidenceEntity entity = entityMapper.toEntity(evidence);

        // 使用 BaseMapper 保存图谱关系证据
        graphEvidenceMapper.insert(entity);
    }

    /**
     * 判断关系证据是否已经保存。
     *
     * @param evidenceId 证据标识
     * @return 已存在返回 true
     */
    public boolean existsEvidence(String evidenceId) {
        return graphEvidenceMapper.selectById(evidenceId) != null;
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
        // 解析节点来源资料标识，复用持久化映射的统一 JSON 转换规则
        List<String> sourceIds = mappingSupport.jsonToStringList(node.getSourceIdsJson());
        if (!sourceIds.contains(documentId)) {
            return;
        }

        // 移除当前来源资料，保留其他文档对同一节点的支撑
        List<String> remainingSourceIds = sourceIds.stream()
                .filter(sourceId -> !documentId.equals(sourceId))
                .toList();
        // 将剩余来源资料标识按统一格式序列化回持久化字段
        node.setSourceIdsJson(mappingSupport.stringListToJson(remainingSourceIds));
        node.setUpdatedAt(updatedAt.toString());

        if (remainingSourceIds.isEmpty()) {
            node.setStatus("stale");
            staleNodeIds.add(node.getId());
        }

        // 使用 MyBatis-Plus 按主键更新节点来源和状态
        graphNodeMapper.updateById(node);
    }

}

package com.flevin.knowgraph.server.service.graph.impl;

import com.flevin.knowgraph.server.model.graph.GraphDataResponse;
import com.flevin.knowgraph.server.model.graph.GraphEdge;
import com.flevin.knowgraph.server.model.graph.GraphEdgeResponse;
import com.flevin.knowgraph.server.model.graph.GraphEvidence;
import com.flevin.knowgraph.server.model.graph.GraphEvidenceResponse;
import com.flevin.knowgraph.server.model.graph.GraphNodeResponse;
import com.flevin.knowgraph.server.model.graph.GraphSummaryResponse;
import com.flevin.knowgraph.server.repository.graph.GraphRepository;
import com.flevin.knowgraph.server.service.graph.GraphResponseMapper;
import com.flevin.knowgraph.server.service.graph.GraphService;
import com.flevin.knowgraph.server.service.space.KnowledgeSpaceService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 知识图谱查询服务实现。
 */
@Service
public class GraphServiceImpl implements GraphService {

    private final GraphRepository graphRepository;
    private final KnowledgeSpaceService knowledgeSpaceService;
    private final GraphResponseMapper responseMapper;

    public GraphServiceImpl(
            GraphRepository graphRepository,
            KnowledgeSpaceService knowledgeSpaceService,
            GraphResponseMapper responseMapper
    ) {
        this.graphRepository = graphRepository;
        this.knowledgeSpaceService = knowledgeSpaceService;
        this.responseMapper = responseMapper;
    }

    /**
     * 获取当前知识图谱的节点、关系和审核数量摘要。
     *
     * @param spaceId 知识空间标识
     * @return 图谱摘要
     */
    @Override
    public GraphSummaryResponse getSummary(String spaceId) {
        // 校验待查询知识空间当前有效
        knowledgeSpaceService.requireActive(spaceId);

        // 查询当前空间有效节点数量
        int nodeCount = graphRepository.countNodes(spaceId);

        // 查询当前空间已确认关系数量
        int edgeCount = graphRepository.countConfirmedEdges(spaceId);

        // 查询当前空间待审核关系数量
        int pendingReviewCount = graphRepository.countPendingEdges(spaceId);

        return new GraphSummaryResponse(
                nodeCount,
                edgeCount,
                pendingReviewCount,
                nodeCount == 0
                        ? "当前知识空间尚未生成图谱节点。"
                        : "图谱摘要已从 SQLite 实时统计。"
        );
    }

    /**
     * 查询指定知识空间的图谱节点、关系和证据。
     *
     * @param spaceId 知识空间标识
     * @return 图谱基础数据
     */
    @Override
    public GraphDataResponse getGraph(String spaceId) {
        // 校验待查询知识空间当前有效
        knowledgeSpaceService.requireActive(spaceId);

        // 批量查询当前空间图谱节点
        List<GraphNodeResponse> nodes = graphRepository.findNodes(spaceId).stream()
                .map(responseMapper::toNodeResponse)
                .toList();

        // 批量查询当前空间图谱关系
        List<GraphEdge> edges = graphRepository.findEdges(spaceId);

        // 一次性查询全部关系证据并按关系标识分组，避免循环访问数据库
        Map<String, List<GraphEvidenceResponse>> evidenceByEdgeId = graphRepository.findEvidencesByEdgeIds(
                        spaceId,
                        edges.stream().map(GraphEdge::id).toList()
                ).stream()
                .collect(Collectors.groupingBy(
                        GraphEvidence::edgeId,
                        Collectors.mapping(responseMapper::toEvidenceResponse, Collectors.toList())
                ));

        // 将关系与其证据合并为前端可直接使用的响应结构
        List<GraphEdgeResponse> edgeResponses = edges.stream()
                .map(edge -> responseMapper.toEdgeResponse(
                        edge,
                        evidenceByEdgeId.getOrDefault(edge.id(), List.of())
                ))
                .toList();
        return new GraphDataResponse(nodes, edgeResponses);
    }
}

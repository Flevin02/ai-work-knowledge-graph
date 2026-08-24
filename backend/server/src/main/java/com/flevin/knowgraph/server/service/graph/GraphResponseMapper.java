package com.flevin.knowgraph.server.service.graph;

import com.flevin.knowgraph.server.model.graph.GraphEdge;
import com.flevin.knowgraph.server.model.graph.GraphEdgeResponse;
import com.flevin.knowgraph.server.model.graph.GraphEvidence;
import com.flevin.knowgraph.server.model.graph.GraphEvidenceResponse;
import com.flevin.knowgraph.server.model.graph.GraphNode;
import com.flevin.knowgraph.server.model.graph.GraphNodeResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * 图谱领域模型到接口响应的映射器。
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface GraphResponseMapper {

    /**
     * 将图谱节点转换为接口响应。
     *
     * @param node 图谱节点领域模型
     * @return 图谱节点响应
     */
    GraphNodeResponse toNodeResponse(GraphNode node);

    /**
     * 将图谱证据转换为接口响应。
     *
     * @param evidence 图谱证据领域模型
     * @return 图谱证据响应
     */
    GraphEvidenceResponse toEvidenceResponse(GraphEvidence evidence);

    /**
     * 将图谱关系及其证据转换为接口响应。
     *
     * @param edge 图谱关系领域模型
     * @param evidence 关系证据响应
     * @return 图谱关系响应
     */
    @Mapping(target = "source", source = "edge.sourceNodeId")
    @Mapping(target = "target", source = "edge.targetNodeId")
    @Mapping(target = "evidence", source = "evidence")
    GraphEdgeResponse toEdgeResponse(
            GraphEdge edge,
            List<GraphEvidenceResponse> evidence
    );
}

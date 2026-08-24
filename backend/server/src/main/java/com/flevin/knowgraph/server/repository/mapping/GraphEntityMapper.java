package com.flevin.knowgraph.server.repository.mapping;

import com.flevin.knowgraph.server.model.graph.GraphEdge;
import com.flevin.knowgraph.server.model.graph.GraphEvidence;
import com.flevin.knowgraph.server.model.graph.GraphNode;
import com.flevin.knowgraph.server.repository.entity.GraphEdgeEntity;
import com.flevin.knowgraph.server.repository.entity.GraphEvidenceEntity;
import com.flevin.knowgraph.server.repository.entity.GraphEvidenceRow;
import com.flevin.knowgraph.server.repository.entity.GraphNodeEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.NullValueCheckStrategy;
import org.mapstruct.ReportingPolicy;

/**
 * 图谱节点、关系和证据领域模型与持久化结果映射器。
 */
@Mapper(
        componentModel = "spring",
        uses = PersistenceMappingSupport.class,
        unmappedTargetPolicy = ReportingPolicy.ERROR,
        nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS
)
public interface GraphEntityMapper {

    /**
     * 将图谱节点实体转换为领域模型。
     *
     * @param entity 图谱节点实体
     * @return 图谱节点领域模型
     */
    @Mapping(target = "type", source = "nodeType")
    @Mapping(target = "sourceIds", source = "sourceIdsJson", qualifiedByName = "jsonToStringList")
    @Mapping(target = "createdAt", source = "createdAt", qualifiedByName = "stringToInstant")
    @Mapping(target = "updatedAt", source = "updatedAt", qualifiedByName = "stringToInstant")
    GraphNode toDomain(GraphNodeEntity entity);

    /**
     * 将图谱节点领域模型转换为持久化实体。
     *
     * @param node 图谱节点领域模型
     * @return 图谱节点实体
     */
    @Mapping(target = "nodeType", source = "type")
    @Mapping(target = "sourceIdsJson", source = "sourceIds", qualifiedByName = "stringListToJson")
    @Mapping(target = "createdAt", source = "createdAt", qualifiedByName = "instantToString")
    @Mapping(target = "updatedAt", source = "updatedAt", qualifiedByName = "instantToString")
    GraphNodeEntity toEntity(GraphNode node);

    /**
     * 将图谱关系实体转换为领域模型。
     *
     * @param entity 图谱关系实体
     * @return 图谱关系领域模型
     */
    @Mapping(target = "type", source = "relationType")
    @Mapping(target = "confidence", source = "confidence", qualifiedByName = "doubleToPrimitive")
    @Mapping(target = "createdAt", source = "createdAt", qualifiedByName = "stringToInstant")
    @Mapping(target = "updatedAt", source = "updatedAt", qualifiedByName = "stringToInstant")
    GraphEdge toDomain(GraphEdgeEntity entity);

    /**
     * 将图谱关系领域模型转换为持久化实体。
     *
     * @param edge 图谱关系领域模型
     * @return 图谱关系实体
     */
    @Mapping(target = "relationType", source = "type")
    @Mapping(target = "createdAt", source = "createdAt", qualifiedByName = "instantToString")
    @Mapping(target = "updatedAt", source = "updatedAt", qualifiedByName = "instantToString")
    GraphEdgeEntity toEntity(GraphEdge edge);

    /**
     * 将证据联合查询行转换为领域模型。
     *
     * @param row 证据联合查询行
     * @return 图谱证据领域模型
     */
    @Mapping(target = "createdAt", source = "createdAt", qualifiedByName = "stringToInstant")
    GraphEvidence toDomain(GraphEvidenceRow row);

    /**
     * 将图谱证据领域模型转换为持久化实体。
     *
     * @param evidence 图谱证据领域模型
     * @return 图谱证据实体
     */
    @Mapping(target = "createdAt", source = "createdAt", qualifiedByName = "instantToString")
    GraphEvidenceEntity toEntity(GraphEvidence evidence);
}

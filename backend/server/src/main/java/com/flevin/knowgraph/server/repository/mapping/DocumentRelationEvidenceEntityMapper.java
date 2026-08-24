package com.flevin.knowgraph.server.repository.mapping;

import com.flevin.knowgraph.server.model.association.DocumentRelationEvidence;
import com.flevin.knowgraph.server.repository.entity.DocumentRelationEvidenceEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.NullValueCheckStrategy;
import org.mapstruct.ReportingPolicy;

/**
 * 文档关系证据领域模型与持久化实体映射器。
 */
@Mapper(
        componentModel = "spring",
        uses = PersistenceMappingSupport.class,
        unmappedTargetPolicy = ReportingPolicy.ERROR,
        nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS
)
public interface DocumentRelationEvidenceEntityMapper {

    /**
     * 将持久化实体转换为文档关系证据领域模型。
     *
     * @param entity 文档关系证据实体
     * @return 文档关系证据领域模型
     */
    @Mapping(target = "createdAt", source = "createdAt", qualifiedByName = "stringToInstant")
    DocumentRelationEvidence toDomain(DocumentRelationEvidenceEntity entity);

    /**
     * 将文档关系证据领域模型转换为持久化实体。
     *
     * @param evidence 文档关系证据领域模型
     * @return 文档关系证据实体
     */
    @Mapping(target = "createdAt", source = "createdAt", qualifiedByName = "instantToString")
    DocumentRelationEvidenceEntity toEntity(DocumentRelationEvidence evidence);
}

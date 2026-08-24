package com.flevin.knowgraph.server.repository.mapping;

import com.flevin.knowgraph.server.model.association.DocumentRelation;
import com.flevin.knowgraph.server.repository.entity.DocumentRelationEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.NullValueCheckStrategy;
import org.mapstruct.ReportingPolicy;

/**
 * 文档关系领域模型与持久化实体映射器。
 */
@Mapper(
        componentModel = "spring",
        uses = PersistenceMappingSupport.class,
        unmappedTargetPolicy = ReportingPolicy.ERROR,
        nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS
)
public interface DocumentRelationEntityMapper {

    /**
     * 将持久化实体转换为文档关系领域模型。
     *
     * @param entity 文档关系实体
     * @return 文档关系领域模型
     */
    @Mapping(target = "confidence", source = "confidence", qualifiedByName = "doubleToPrimitive")
    @Mapping(target = "createdAt", source = "createdAt", qualifiedByName = "stringToInstant")
    @Mapping(target = "updatedAt", source = "updatedAt", qualifiedByName = "stringToInstant")
    DocumentRelation toDomain(DocumentRelationEntity entity);

    /**
     * 将文档关系领域模型转换为持久化实体。
     *
     * @param relation 文档关系领域模型
     * @return 文档关系实体
     */
    @Mapping(target = "createdAt", source = "createdAt", qualifiedByName = "instantToString")
    @Mapping(target = "updatedAt", source = "updatedAt", qualifiedByName = "instantToString")
    DocumentRelationEntity toEntity(DocumentRelation relation);
}

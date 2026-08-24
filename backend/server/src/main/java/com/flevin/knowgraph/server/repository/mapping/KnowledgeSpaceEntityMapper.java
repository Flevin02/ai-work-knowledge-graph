package com.flevin.knowgraph.server.repository.mapping;

import com.flevin.knowgraph.server.model.space.KnowledgeSpace;
import com.flevin.knowgraph.server.repository.entity.KnowledgeSpaceEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.NullValueCheckStrategy;
import org.mapstruct.ReportingPolicy;

/**
 * 知识空间领域模型与持久化实体映射器。
 */
@Mapper(
        componentModel = "spring",
        uses = PersistenceMappingSupport.class,
        unmappedTargetPolicy = ReportingPolicy.ERROR,
        nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS
)
public interface KnowledgeSpaceEntityMapper {

    /**
     * 将持久化实体转换为知识空间领域模型。
     *
     * @param entity 知识空间实体
     * @return 知识空间领域模型
     */
    @Mapping(target = "createdAt", source = "createdAt", qualifiedByName = "stringToInstant")
    @Mapping(target = "updatedAt", source = "updatedAt", qualifiedByName = "stringToInstant")
    KnowledgeSpace toDomain(KnowledgeSpaceEntity entity);

    /**
     * 将知识空间领域模型转换为持久化实体。
     *
     * @param space 知识空间领域模型
     * @return 知识空间实体
     */
    @Mapping(target = "createdAt", source = "createdAt", qualifiedByName = "instantToString")
    @Mapping(target = "updatedAt", source = "updatedAt", qualifiedByName = "instantToString")
    KnowledgeSpaceEntity toEntity(KnowledgeSpace space);
}

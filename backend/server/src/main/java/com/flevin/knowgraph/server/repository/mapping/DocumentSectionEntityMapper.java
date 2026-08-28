package com.flevin.knowgraph.server.repository.mapping;

import com.flevin.knowgraph.server.model.ai.rag.DocumentSectionFact;
import com.flevin.knowgraph.server.repository.entity.DocumentSectionEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.NullValueCheckStrategy;
import org.mapstruct.ReportingPolicy;

/**
 * 来源资料章节领域事实与持久化实体映射器。
 */
@Mapper(
        componentModel = "spring",
        uses = PersistenceMappingSupport.class,
        unmappedTargetPolicy = ReportingPolicy.ERROR,
        nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS
)
public interface DocumentSectionEntityMapper {

    /**
     * 将章节持久化实体转换为领域事实。
     *
     * @param entity 章节持久化实体
     * @return 章节领域事实
     */
    @Mapping(target = "level", source = "level", qualifiedByName = "integerToInt")
    @Mapping(target = "ordinal", source = "ordinal", qualifiedByName = "integerToInt")
    @Mapping(target = "startOffset", source = "startOffset", qualifiedByName = "integerToInt")
    @Mapping(target = "endOffset", source = "endOffset", qualifiedByName = "integerToInt")
    @Mapping(target = "createdAt", source = "createdAt", qualifiedByName = "stringToInstant")
    @Mapping(target = "updatedAt", source = "updatedAt", qualifiedByName = "stringToInstant")
    DocumentSectionFact toDomain(DocumentSectionEntity entity);

    /**
     * 将章节领域事实转换为持久化实体。
     *
     * @param fact 章节领域事实
     * @return 章节持久化实体
     */
    @Mapping(target = "createdAt", source = "createdAt", qualifiedByName = "instantToString")
    @Mapping(target = "updatedAt", source = "updatedAt", qualifiedByName = "instantToString")
    DocumentSectionEntity toEntity(DocumentSectionFact fact);
}

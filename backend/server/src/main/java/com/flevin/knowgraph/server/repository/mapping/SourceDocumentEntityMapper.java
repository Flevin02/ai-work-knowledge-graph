package com.flevin.knowgraph.server.repository.mapping;

import com.flevin.knowgraph.server.model.document.SourceDocument;
import com.flevin.knowgraph.server.repository.entity.SourceDocumentEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.NullValueCheckStrategy;
import org.mapstruct.ReportingPolicy;

/**
 * 来源资料领域模型与持久化实体映射器。
 */
@Mapper(
        componentModel = "spring",
        uses = PersistenceMappingSupport.class,
        unmappedTargetPolicy = ReportingPolicy.ERROR,
        nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS
)
public interface SourceDocumentEntityMapper {

    /**
     * 将持久化实体转换为来源资料领域模型。
     *
     * @param entity 来源资料实体
     * @return 来源资料领域模型
     */
    @Mapping(target = "documentType", source = "documentType", qualifiedByName = "stringToDocumentType")
    @Mapping(target = "fileSize", source = "fileSize", qualifiedByName = "longToPrimitive")
    @Mapping(target = "importedAt", source = "importedAt", qualifiedByName = "stringToInstant")
    @Mapping(target = "updatedAt", source = "updatedAt", qualifiedByName = "stringToInstant")
    SourceDocument toDomain(SourceDocumentEntity entity);

    /**
     * 将来源资料领域模型转换为持久化实体。
     *
     * @param document 来源资料领域模型
     * @return 来源资料实体
     */
    @Mapping(target = "documentType", source = "documentType", qualifiedByName = "documentTypeToString")
    @Mapping(target = "importedAt", source = "importedAt", qualifiedByName = "instantToString")
    @Mapping(target = "updatedAt", source = "updatedAt", qualifiedByName = "instantToString")
    SourceDocumentEntity toEntity(SourceDocument document);
}

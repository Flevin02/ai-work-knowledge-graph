package com.flevin.knowgraph.server.repository.mapping;

import com.flevin.knowgraph.server.model.association.DocumentAssociationRun;
import com.flevin.knowgraph.server.repository.entity.DocumentAssociationRunEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.NullValueCheckStrategy;
import org.mapstruct.ReportingPolicy;

/**
 * 文档关联运行领域模型与持久化实体映射器。
 */
@Mapper(
        componentModel = "spring",
        uses = PersistenceMappingSupport.class,
        unmappedTargetPolicy = ReportingPolicy.ERROR,
        nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS
)
public interface DocumentAssociationRunEntityMapper {

    /**
     * 将持久化实体转换为文档关联运行领域模型。
     *
     * @param entity 文档关联运行实体
     * @return 文档关联运行领域模型
     */
    @Mapping(target = "candidateCount", source = "candidateCount", qualifiedByName = "integerToInt")
    @Mapping(target = "comparedCount", source = "comparedCount", qualifiedByName = "integerToInt")
    @Mapping(target = "suggestionCount", source = "suggestionCount", qualifiedByName = "integerToInt")
    @Mapping(target = "tagCandidateCount", source = "tagCandidateCount", qualifiedByName = "integerToInt")
    @Mapping(target = "keywordCandidateCount", source = "keywordCandidateCount", qualifiedByName = "integerToInt")
    @Mapping(target = "semanticCandidateCount", source = "semanticCandidateCount", qualifiedByName = "integerToInt")
    @Mapping(target = "modelRequestCount", source = "modelRequestCount", qualifiedByName = "integerToInt")
    @Mapping(target = "retryCount", source = "retryCount", qualifiedByName = "integerToInt")
    @Mapping(target = "createdAt", source = "createdAt", qualifiedByName = "stringToInstant")
    @Mapping(target = "completedAt", source = "completedAt", qualifiedByName = "stringToInstant")
    DocumentAssociationRun toDomain(DocumentAssociationRunEntity entity);

    /**
     * 将文档关联运行领域模型转换为持久化实体。
     *
     * @param run 文档关联运行领域模型
     * @return 文档关联运行实体
     */
    @Mapping(target = "createdAt", source = "createdAt", qualifiedByName = "instantToString")
    @Mapping(target = "completedAt", source = "completedAt", qualifiedByName = "instantToString")
    DocumentAssociationRunEntity toEntity(DocumentAssociationRun run);
}

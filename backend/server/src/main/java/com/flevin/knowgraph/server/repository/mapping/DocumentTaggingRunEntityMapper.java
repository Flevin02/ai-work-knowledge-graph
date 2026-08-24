package com.flevin.knowgraph.server.repository.mapping;

import com.flevin.knowgraph.server.model.tag.DocumentTaggingRun;
import com.flevin.knowgraph.server.repository.entity.DocumentTaggingRunEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.NullValueCheckStrategy;
import org.mapstruct.ReportingPolicy;

/**
 * 文档标签运行领域模型与持久化实体映射器。
 */
@Mapper(
        componentModel = "spring",
        uses = PersistenceMappingSupport.class,
        unmappedTargetPolicy = ReportingPolicy.ERROR,
        nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS
)
public interface DocumentTaggingRunEntityMapper {

    /**
     * 将持久化实体转换为标签运行领域模型。
     *
     * @param entity 标签运行持久化实体
     * @return 标签运行领域模型
     */
    @Mapping(target = "chunkCount", source = "chunkCount", qualifiedByName = "integerToInt")
    @Mapping(target = "contextCharacterCount", source = "contextCharacterCount", qualifiedByName = "integerToInt")
    @Mapping(target = "suggestionCount", source = "suggestionCount", qualifiedByName = "integerToInt")
    @Mapping(target = "evidenceFailureCount", source = "evidenceFailureCount", qualifiedByName = "integerToInt")
    @Mapping(target = "modelRequestCount", source = "modelRequestCount", qualifiedByName = "integerToInt")
    @Mapping(target = "retryCount", source = "retryCount", qualifiedByName = "integerToInt")
    @Mapping(target = "createdAt", source = "createdAt", qualifiedByName = "stringToInstant")
    @Mapping(target = "completedAt", source = "completedAt", qualifiedByName = "stringToInstant")
    DocumentTaggingRun toDomain(DocumentTaggingRunEntity entity);

    /**
     * 将标签运行领域模型转换为持久化实体。
     *
     * @param run 标签运行领域模型
     * @return 标签运行持久化实体
     */
    @Mapping(target = "createdAt", source = "createdAt", qualifiedByName = "instantToString")
    @Mapping(target = "completedAt", source = "completedAt", qualifiedByName = "instantToString")
    DocumentTaggingRunEntity toEntity(DocumentTaggingRun run);
}

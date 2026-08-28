package com.flevin.knowgraph.server.repository.mapping;

import com.flevin.knowgraph.server.model.ai.rag.DocumentChunkFact;
import com.flevin.knowgraph.server.repository.entity.DocumentChunkEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.NullValueCheckStrategy;
import org.mapstruct.ReportingPolicy;

/**
 * 来源资料分片领域事实与持久化实体映射器。
 */
@Mapper(
        componentModel = "spring",
        uses = PersistenceMappingSupport.class,
        unmappedTargetPolicy = ReportingPolicy.ERROR,
        nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS
)
public interface DocumentChunkEntityMapper {

    /**
     * 将分片持久化实体转换为领域事实。
     *
     * @param entity 分片持久化实体
     * @return 分片领域事实
     */
    @Mapping(target = "ordinal", source = "ordinal", qualifiedByName = "integerToInt")
    @Mapping(target = "documentOrdinal", source = "documentOrdinal", qualifiedByName = "integerToInt")
    @Mapping(target = "startOffset", source = "startOffset", qualifiedByName = "integerToInt")
    @Mapping(target = "endOffset", source = "endOffset", qualifiedByName = "integerToInt")
    @Mapping(target = "createdAt", source = "createdAt", qualifiedByName = "stringToInstant")
    @Mapping(target = "updatedAt", source = "updatedAt", qualifiedByName = "stringToInstant")
    DocumentChunkFact toDomain(DocumentChunkEntity entity);

    /**
     * 将分片领域事实转换为持久化实体。
     *
     * @param fact 分片领域事实
     * @return 分片持久化实体
     */
    @Mapping(target = "createdAt", source = "createdAt", qualifiedByName = "instantToString")
    @Mapping(target = "updatedAt", source = "updatedAt", qualifiedByName = "instantToString")
    DocumentChunkEntity toEntity(DocumentChunkFact fact);
}

package com.flevin.knowgraph.server.repository.mapping;

import com.flevin.knowgraph.server.model.ai.rag.DocumentChunkIndexStateFact;
import com.flevin.knowgraph.server.repository.entity.DocumentChunkIndexStateEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.NullValueCheckStrategy;
import org.mapstruct.ReportingPolicy;

/**
 * 分片向量索引状态领域事实与持久化实体映射器。
 */
@Mapper(
        componentModel = "spring",
        uses = PersistenceMappingSupport.class,
        unmappedTargetPolicy = ReportingPolicy.ERROR,
        nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS
)
public interface DocumentChunkIndexStateEntityMapper {

    /**
     * 将索引状态持久化实体转换为领域事实。
     *
     * @param entity 索引状态持久化实体
     * @return 索引状态领域事实
     */
    @Mapping(target = "dimension", source = "dimension", qualifiedByName = "integerToInt")
    @Mapping(target = "vector", source = "vectorJson", qualifiedByName = "jsonToEmbeddingVector")
    @Mapping(target = "createdAt", source = "createdAt", qualifiedByName = "stringToInstant")
    @Mapping(target = "updatedAt", source = "updatedAt", qualifiedByName = "stringToInstant")
    DocumentChunkIndexStateFact toDomain(DocumentChunkIndexStateEntity entity);

    /**
     * 将索引状态领域事实转换为持久化实体。
     *
     * @param fact 索引状态领域事实
     * @return 索引状态持久化实体
     */
    @Mapping(target = "vectorJson", source = "vector", qualifiedByName = "embeddingVectorToJson")
    @Mapping(target = "createdAt", source = "createdAt", qualifiedByName = "instantToString")
    @Mapping(target = "updatedAt", source = "updatedAt", qualifiedByName = "instantToString")
    DocumentChunkIndexStateEntity toEntity(DocumentChunkIndexStateFact fact);
}

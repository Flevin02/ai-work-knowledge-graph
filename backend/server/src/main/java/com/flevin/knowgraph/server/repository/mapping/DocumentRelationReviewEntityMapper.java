package com.flevin.knowgraph.server.repository.mapping;

import com.flevin.knowgraph.server.model.association.DocumentRelationReview;
import com.flevin.knowgraph.server.repository.entity.DocumentRelationReviewEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.NullValueCheckStrategy;
import org.mapstruct.ReportingPolicy;

/**
 * 文档关系审核历史领域模型与持久化实体映射器。
 */
@Mapper(
        componentModel = "spring",
        uses = PersistenceMappingSupport.class,
        unmappedTargetPolicy = ReportingPolicy.ERROR,
        nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS
)
public interface DocumentRelationReviewEntityMapper {

    /**
     * 将持久化实体转换为审核历史领域模型。
     *
     * @param entity 审核历史实体
     * @return 审核历史领域模型
     */
    @Mapping(target = "createdAt", source = "createdAt", qualifiedByName = "stringToInstant")
    DocumentRelationReview toDomain(DocumentRelationReviewEntity entity);

    /**
     * 将审核历史领域模型转换为持久化实体。
     *
     * @param review 审核历史领域模型
     * @return 审核历史实体
     */
    @Mapping(target = "createdAt", source = "createdAt", qualifiedByName = "instantToString")
    DocumentRelationReviewEntity toEntity(DocumentRelationReview review);
}

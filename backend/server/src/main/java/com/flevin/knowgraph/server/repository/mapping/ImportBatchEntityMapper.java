package com.flevin.knowgraph.server.repository.mapping;

import com.flevin.knowgraph.server.model.document.ImportBatch;
import com.flevin.knowgraph.server.repository.entity.ImportBatchEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.NullValueCheckStrategy;
import org.mapstruct.ReportingPolicy;

/**
 * 来源资料导入批次领域模型到持久化实体的映射器。
 */
@Mapper(
        componentModel = "spring",
        uses = PersistenceMappingSupport.class,
        unmappedTargetPolicy = ReportingPolicy.ERROR,
        nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS
)
public interface ImportBatchEntityMapper {

    /**
     * 将导入批次领域模型转换为持久化实体。
     *
     * @param batch 导入批次领域模型
     * @return 导入批次实体
     */
    @Mapping(target = "createdAt", source = "createdAt", qualifiedByName = "instantToString")
    @Mapping(target = "completedAt", source = "completedAt", qualifiedByName = "instantToString")
    ImportBatchEntity toEntity(ImportBatch batch);
}

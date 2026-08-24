package com.flevin.knowgraph.server.repository.mapping;

import com.flevin.knowgraph.server.model.ai.DocumentExtractionOverview;
import com.flevin.knowgraph.server.model.ai.AiExtractionRunSummary;
import com.flevin.knowgraph.server.repository.entity.AiExtractionRunEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.NullValueCheckStrategy;
import org.mapstruct.ReportingPolicy;

/**
 * AI 抽取运行实体到列表概览的映射器。
 */
@Mapper(
        componentModel = "spring",
        uses = PersistenceMappingSupport.class,
        unmappedTargetPolicy = ReportingPolicy.ERROR,
        nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS
)
public interface AiExtractionRunEntityMapper {

    /**
     * 将抽取运行实体转换为来源资料列表使用的概览。
     *
     * @param entity 抽取运行实体
     * @return 抽取运行概览
     */
    @Mapping(target = "documentId", source = "sourceDocumentId")
    @Mapping(target = "extractionId", source = "id")
    @Mapping(target = "startedAt", source = "createdAt", qualifiedByName = "stringToInstant")
    @Mapping(target = "completedAt", source = "completedAt", qualifiedByName = "stringToInstant")
    DocumentExtractionOverview toOverview(AiExtractionRunEntity entity);

    /**
     * 将抽取运行实体转换为历史列表摘要。
     *
     * @param entity 抽取运行实体
     * @return 抽取运行摘要
     */
    @Mapping(target = "extractionId", source = "id")
    @Mapping(target = "sectionCount", source = "sectionCount", qualifiedByName = "integerToInt")
    @Mapping(target = "chunkCount", source = "chunkCount", qualifiedByName = "integerToInt")
    @Mapping(target = "createdAt", source = "createdAt", qualifiedByName = "stringToInstant")
    @Mapping(target = "completedAt", source = "completedAt", qualifiedByName = "stringToInstant")
    AiExtractionRunSummary toSummary(AiExtractionRunEntity entity);
}

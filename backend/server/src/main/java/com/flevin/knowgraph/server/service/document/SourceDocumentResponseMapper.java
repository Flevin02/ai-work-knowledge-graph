package com.flevin.knowgraph.server.service.document;

import com.flevin.knowgraph.server.model.ai.DocumentExtractionOverview;
import com.flevin.knowgraph.server.model.document.SourceDocument;
import com.flevin.knowgraph.server.model.document.SourceDocumentExtractionSummary;
import com.flevin.knowgraph.server.model.document.SourceDocumentResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/**
 * 来源资料领域模型与抽取概览到安全接口响应的映射器。
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface SourceDocumentResponseMapper {

    /**
     * 将抽取概览转换为来源资料卡片使用的抽取摘要。
     *
     * @param overview 抽取概览
     * @return 抽取摘要
     */
    SourceDocumentExtractionSummary toExtractionSummary(DocumentExtractionOverview overview);

    /**
     * 将来源资料及服务层确定的摘要字段转换为安全响应。
     *
     * @param document 来源资料领域模型
     * @param excerpt 展示摘要
     * @param latestExtraction 最近抽取摘要
     * @param latestCompletedExtractionId 最近成功抽取标识
     * @return 不暴露存储路径和完整原文的来源资料响应
     */
    @Mapping(target = "id", source = "document.id")
    @Mapping(target = "spaceId", source = "document.spaceId")
    @Mapping(target = "name", source = "document.name")
    @Mapping(target = "kind", source = "document.kind")
    @Mapping(target = "documentType", source = "document.documentType")
    @Mapping(target = "contentHash", source = "document.contentHash")
    @Mapping(target = "excerpt", source = "excerpt")
    @Mapping(target = "status", source = "document.status")
    @Mapping(target = "fileSize", source = "document.fileSize")
    @Mapping(target = "importedAt", source = "document.importedAt")
    @Mapping(target = "updatedAt", source = "document.updatedAt")
    @Mapping(target = "latestExtraction", source = "latestExtraction")
    @Mapping(target = "latestCompletedExtractionId", source = "latestCompletedExtractionId")
    SourceDocumentResponse toResponse(
            SourceDocument document,
            String excerpt,
            SourceDocumentExtractionSummary latestExtraction,
            String latestCompletedExtractionId
    );
}

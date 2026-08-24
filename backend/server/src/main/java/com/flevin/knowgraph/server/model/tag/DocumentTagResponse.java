package com.flevin.knowgraph.server.model.tag;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * 可审核文档标签、证据和审核历史响应。
 *
 * @param id 文档标签关系标识
 * @param tagId 标签定义标识
 * @param name 标签展示名称
 * @param normalizedKey 标签规范化键
 * @param sourceType 标签来源
 * @param status 当前审核状态
 * @param confidence 模型或规则置信度
 * @param extractionRunId 标签运行标识
 * @param evidences 已逐字反查的标签证据
 * @param reviews 不可变审核历史
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间
 */
@Schema(description = "可审核文档标签及其证据和历史")
public record DocumentTagResponse(
        @Schema(description = "文档标签关系标识") String id,
        @Schema(description = "标签定义标识") String tagId,
        @Schema(description = "标签展示名称", example = "年会筹备") String name,
        @Schema(description = "标签规范化键", example = "年会筹备") String normalizedKey,
        @Schema(description = "标签来源", example = "ai") String sourceType,
        @Schema(description = "审核状态", example = "suggested") String status,
        @Schema(description = "模型或规则置信度", example = "0.9") Double confidence,
        @Schema(description = "标签运行标识") String extractionRunId,
        @Schema(description = "已完成逐字反查的证据") List<Evidence> evidences,
        @Schema(description = "不可变审核历史") List<Review> reviews,
        @Schema(description = "创建时间") Instant createdAt,
        @Schema(description = "最近更新时间") Instant updatedAt
) {

    public DocumentTagResponse {
        evidences = List.copyOf(evidences);
        reviews = List.copyOf(reviews);
    }

    /**
     * 文档标签的可定位原文证据。
     *
     * @param id 证据标识
     * @param sourceDocumentId 证据所在来源资料标识
     * @param chunkId 分片标识
     * @param sectionPath 章节路径
     * @param quote 逐字原文
     * @param startOffset 原文起始偏移
     * @param endOffset 原文结束偏移
     */
    @Schema(description = "文档标签的可定位原文证据")
    public record Evidence(
            @Schema(description = "证据标识") String id,
            @Schema(description = "证据所在来源资料标识") String sourceDocumentId,
            @Schema(description = "分片标识") String chunkId,
            @Schema(description = "章节路径") String sectionPath,
            @Schema(description = "逐字原文") String quote,
            @Schema(description = "原文起始偏移") Integer startOffset,
            @Schema(description = "原文结束偏移") Integer endOffset
    ) {
    }

    /**
     * 文档标签审核历史响应。
     *
     * @param id 审核记录标识
     * @param action 审核动作
     * @param reason 可选审核说明
     * @param operatorName 操作者展示名称
     * @param createdAt 审核时间
     */
    @Schema(description = "文档标签不可变审核历史")
    public record Review(
            @Schema(description = "审核记录标识") String id,
            @Schema(description = "审核动作", example = "accept") String action,
            @Schema(description = "审核说明") String reason,
            @Schema(description = "操作者展示名称", example = "local-user") String operatorName,
            @Schema(description = "审核时间") Instant createdAt
    ) {
    }
}

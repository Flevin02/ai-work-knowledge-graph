package com.flevin.knowgraph.server.model.association;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * 可审核文档关系及其服务端已校验证据。
 *
 * @param id 文档关系标识
 * @param sourceDocumentId 最终关系主体文档标识
 * @param targetDocumentId 最终关系客体文档标识
 * @param relationType 文档关系类型
 * @param direction 模型相对当前文档表达的方向
 * @param status 审核状态
 * @param generationMode 关系生成方式
 * @param confidence 模型置信度
 * @param reason 关系判断原因
 * @param associationRunId 产生该建议的关联运行标识
 * @param evidences 已逐字反查的关系证据
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间
 */
@Schema(description = "可审核文档关系及其证据")
public record DocumentRelationResponse(
        @Schema(description = "文档关系标识") Long id,
        @Schema(description = "关系主体文档标识") Long sourceDocumentId,
        @Schema(description = "关系客体文档标识") Long targetDocumentId,
        @Schema(description = "关系类型", example = "updates") String relationType,
        @Schema(description = "关系方向", example = "current_to_candidate") String direction,
        @Schema(description = "审核状态", example = "suggested") String status,
        @Schema(description = "生成方式", example = "explicit_reference") String generationMode,
        @Schema(description = "模型置信度", example = "0.86") double confidence,
        @Schema(description = "关系判断原因") String reason,
        @Schema(description = "文档关联运行标识") Long associationRunId,
        @Schema(description = "已完成逐字反查的证据") List<Evidence> evidences,
        @Schema(description = "创建时间") Instant createdAt,
        @Schema(description = "最近更新时间") Instant updatedAt
) {

    public DocumentRelationResponse {
        evidences = List.copyOf(evidences);
    }

    /**
     * 文档关系的可定位原文证据。
     *
     * @param id 证据标识
     * @param sourceDocumentId 证据所在文档标识
     * @param chunkId 证据所在分片标识
     * @param sectionPath 章节路径
     * @param quote 可逐字反查的原文
     * @param startOffset 原文起始偏移
     * @param endOffset 原文结束偏移
     * @param evidenceRole 证据相对最终关系的角色
     */
    @Schema(description = "文档关系的可定位原文证据")
    public record Evidence(
            @Schema(description = "证据标识") Long id,
            @Schema(description = "证据所在文档标识") Long sourceDocumentId,
            @Schema(description = "分片标识") String chunkId,
            @Schema(description = "章节路径") String sectionPath,
            @Schema(description = "逐字原文") String quote,
            @Schema(description = "原文起始偏移") Integer startOffset,
            @Schema(description = "原文结束偏移") Integer endOffset,
            @Schema(description = "证据角色", example = "source") String evidenceRole
    ) {
    }
}

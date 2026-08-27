package com.flevin.knowgraph.server.model.documentgraph;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * 文档关系图中的已确认文档关系边。
 *
 * @param id 文档关系标识
 * @param sourceDocumentId 关系主体文档标识
 * @param targetDocumentId 关系客体文档标识
 * @param relationType 关系类型
 * @param direction 关系方向
 * @param status 审核状态；当前图默认只返回 confirmed
 * @param confidence 关系置信度
 * @param reason 关系判断原因
 * @param evidences 已校验的关系原文证据
 * @param updatedAt 最近更新时间
 */
@Schema(description = "文档关系图中的文档关系边")
public record DocumentGraphEdgeResponse(
        @Schema(description = "文档关系标识") Long id,
        @Schema(description = "关系主体文档标识") Long sourceDocumentId,
        @Schema(description = "关系客体文档标识") Long targetDocumentId,
        @Schema(description = "关系类型", example = "references") String relationType,
        @Schema(description = "关系方向", example = "current_to_candidate") String direction,
        @Schema(description = "审核状态", example = "confirmed") String status,
        @Schema(description = "关系置信度", example = "0.91") double confidence,
        @Schema(description = "关系判断原因") String reason,
        @Schema(description = "关系原文证据") List<Evidence> evidences,
        @Schema(description = "最近更新时间") Instant updatedAt
) {

    public DocumentGraphEdgeResponse {
        evidences = List.copyOf(evidences);
    }

    /**
     * 文档关系图边使用的可定位原文证据。
     *
     * @param id 证据标识
     * @param sourceDocumentId 证据所在文档标识
     * @param chunkId 证据所在分片标识
     * @param sectionPath 章节路径
     * @param quote 逐字原文
     * @param startOffset 原文起始偏移
     * @param endOffset 原文结束偏移
     * @param evidenceRole 证据角色
     */
    @Schema(description = "文档关系图边的原文证据")
    public record Evidence(
            @Schema(description = "证据标识") Long id,
            @Schema(description = "证据所在文档标识") Long sourceDocumentId,
            @Schema(description = "证据所在分片标识") String chunkId,
            @Schema(description = "章节路径") String sectionPath,
            @Schema(description = "逐字原文") String quote,
            @Schema(description = "原文起始偏移") Integer startOffset,
            @Schema(description = "原文结束偏移") Integer endOffset,
            @Schema(description = "证据角色", example = "source") String evidenceRole
    ) {
    }
}

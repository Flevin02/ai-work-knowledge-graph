package com.flevin.knowgraph.server.model.tag;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * 已通过服务端校验的待审核标签建议。
 *
 * @param id 文档标签关系标识
 * @param tagId 标签字典标识
 * @param name 标签展示名称
 * @param status 当前审核状态
 * @param confidence 模型置信度
 * @param evidences 已校验逐字证据
 * @param createdAt 创建时间
 */
@Schema(description = "待审核文档标签建议")
public record DocumentTagSuggestionResponse(
        @Schema(description = "文档标签关系标识") Long id,
        @Schema(description = "标签字典标识") Long tagId,
        @Schema(description = "标签展示名称", example = "年会筹备") String name,
        @Schema(description = "审核状态", example = "suggested") String status,
        @Schema(description = "模型置信度", example = "0.91") Double confidence,
        @Schema(description = "逐字证据") List<Evidence> evidences,
        @Schema(description = "创建时间") Instant createdAt
) {

    public DocumentTagSuggestionResponse {
        evidences = List.copyOf(evidences);
    }

    /**
     * 标签建议的可定位逐字证据。
     *
     * @param sourceDocumentId 来源资料标识
     * @param chunkId 分片标识
     * @param sectionPath 章节路径
     * @param quote 原文引用
     * @param startOffset 原文起始偏移
     * @param endOffset 原文结束偏移
     */
    @Schema(description = "文档标签逐字证据")
    public record Evidence(
            @Schema(description = "来源资料标识") Long sourceDocumentId,
            @Schema(description = "分片标识") String chunkId,
            @Schema(description = "章节路径") String sectionPath,
            @Schema(description = "逐字原文") String quote,
            @Schema(description = "原文起始偏移") Integer startOffset,
            @Schema(description = "原文结束偏移") Integer endOffset
    ) {
    }
}

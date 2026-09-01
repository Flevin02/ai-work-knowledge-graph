package com.flevin.knowgraph.server.model.conversation;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 回答引用及可定位来源。
 *
 * @param citationId 引用标识
 * @param messageId 被引用支撑的回答消息标识
 * @param sourceDocumentId 引用来源资料标识
 * @param sourceDocumentName 引用来源资料名称
 * @param sourceStale 来源文档版本是否已变化；true 表示引用对应的原文版本已失效
 * @param chunkRecordId 分片事实标识
 * @param chunkId 文档内稳定分片标识
 * @param sectionPath 分片所属章节路径
 * @param quote 可逐字反查的原文片段
 * @param startOffset 原文起始偏移，半开区间
 * @param endOffset 原文结束偏移，不包含该位置字符
 * @param citationOrder 引用在回答中的顺序，从 1 开始
 * @param validationStatus 引用校验状态：verified 或 stale
 */
@Schema(description = "回答引用及可定位来源")
public record MessageCitationResponse(
        @Schema(description = "引用标识") Long citationId,
        @Schema(description = "回答消息标识") Long messageId,
        @Schema(description = "引用来源资料标识") Long sourceDocumentId,
        @Schema(description = "引用来源资料名称", example = "2026 年会活动方案-v1.md") String sourceDocumentName,
        @Schema(description = "来源文档版本是否已失效", example = "false") boolean sourceStale,
        @Schema(description = "分片事实标识") Long chunkRecordId,
        @Schema(description = "文档内稳定分片标识", example = "chunk-3") String chunkId,
        @Schema(description = "分片所属章节路径", example = "场地安排") String sectionPath,
        @Schema(description = "可逐字反查的原文片段") String quote,
        @Schema(description = "原文起始偏移，半开区间") Integer startOffset,
        @Schema(description = "原文结束偏移，不包含该位置字符") Integer endOffset,
        @Schema(description = "引用在回答中的顺序", example = "1") int citationOrder,
        @Schema(description = "引用校验状态", example = "verified") String validationStatus
) {
}

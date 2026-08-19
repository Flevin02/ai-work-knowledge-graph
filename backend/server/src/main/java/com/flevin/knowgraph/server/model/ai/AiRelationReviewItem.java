package com.flevin.knowgraph.server.model.ai;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 一条 AI 候选关系的审核决定。
 *
 * @param chunkId 来源分片标识
 * @param relationIndex 当前分片内关系顺序，从 0 开始
 * @param action 采纳或拒绝
 * @param reason 可选的审核说明
 */
@Schema(description = "单条 AI 候选关系审核决定")
public record AiRelationReviewItem(
        @Schema(description = "来源分片标识", example = "section-1-chunk-1")
        @NotBlank String chunkId,
        @Schema(description = "当前分片内关系顺序，从 0 开始", example = "0")
        @Min(0) int relationIndex,
        @Schema(description = "审核动作", example = "ACCEPT")
        @NotNull AiRelationReviewAction action,
        @Schema(description = "拒绝原因或审核说明")
        String reason
) {
}

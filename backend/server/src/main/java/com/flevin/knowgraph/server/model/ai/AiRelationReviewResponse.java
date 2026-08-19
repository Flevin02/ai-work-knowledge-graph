package com.flevin.knowgraph.server.model.ai;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 一批 AI 候选关系审核后的统计结果。
 *
 * @param acceptedCount 本次采纳数量
 * @param rejectedCount 本次拒绝数量
 * @param pendingCount 当前抽取结果仍待审核数量
 */
@Schema(description = "AI 候选关系审核结果")
public record AiRelationReviewResponse(
        @Schema(description = "本次采纳数量", example = "2")
        int acceptedCount,
        @Schema(description = "本次拒绝数量", example = "1")
        int rejectedCount,
        @Schema(description = "当前抽取结果仍待审核数量", example = "0")
        int pendingCount
) {
}

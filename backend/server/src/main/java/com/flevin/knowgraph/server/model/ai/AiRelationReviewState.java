package com.flevin.knowgraph.server.model.ai;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 已持久化的候选关系审核状态。
 *
 * @param chunkId 来源分片标识
 * @param relationIndex 当前分片内关系顺序，从 0 开始
 * @param action 已保存的审核动作
 */
@Schema(description = "已持久化的 AI 候选关系审核状态")
public record AiRelationReviewState(
        @Schema(description = "来源分片标识")
        String chunkId,
        @Schema(description = "当前分片内关系顺序，从 0 开始")
        int relationIndex,
        @Schema(description = "已保存的审核动作", example = "ACCEPT")
        AiRelationReviewAction action
) {
}

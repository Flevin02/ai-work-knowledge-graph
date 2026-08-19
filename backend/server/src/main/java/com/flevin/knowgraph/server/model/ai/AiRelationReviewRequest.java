package com.flevin.knowgraph.server.model.ai;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 一批 AI 候选关系审核请求。
 *
 * @param reviews 当前批次的单条审核决定
 * @param operatorName 操作者展示名称
 */
@Schema(description = "AI 候选关系批量审核请求")
public record AiRelationReviewRequest(
        @Schema(description = "单条审核决定，至少一条")
        @NotEmpty @Size(max = 100) List<@Valid AiRelationReviewItem> reviews,
        @Schema(description = "操作者展示名称", example = "local-user")
        @Size(max = 100) String operatorName
) {
}

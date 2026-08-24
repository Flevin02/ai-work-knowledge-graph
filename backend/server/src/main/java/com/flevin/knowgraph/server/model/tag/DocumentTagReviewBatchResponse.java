package com.flevin.knowgraph.server.model.tag;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 文档标签批量审核结果。
 *
 * @param acceptedCount 本次采纳数量
 * @param rejectedCount 本次拒绝数量
 * @param tags 审核后的标签快照及历史
 */
@Schema(description = "文档标签批量审核结果")
public record DocumentTagReviewBatchResponse(
        @Schema(description = "本次采纳数量", example = "1") int acceptedCount,
        @Schema(description = "本次拒绝数量", example = "1") int rejectedCount,
        @Schema(description = "审核后的标签快照及历史") List<DocumentTagResponse> tags
) {

    public DocumentTagReviewBatchResponse {
        tags = List.copyOf(tags);
    }
}

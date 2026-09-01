package com.flevin.knowgraph.server.model.tag;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 跨文档批量审核结果统计。
 *
 * @param requestedDocumentCount 本次请求的资料数量
 * @param reviewedDocumentCount 实际存在 suggested 标签并被审核的资料数量
 * @param acceptedCount 采纳的标签数量
 * @param rejectedCount 拒绝的标签数量
 */
@Schema(description = "跨文档批量审核文档标签结果")
public record DocumentTagBatchReviewResponse(
        @Schema(description = "本次请求的资料数量", example = "3")
        int requestedDocumentCount,
        @Schema(description = "实际存在待审标签并被审核的资料数量", example = "2")
        int reviewedDocumentCount,
        @Schema(description = "采纳的标签数量", example = "10")
        int acceptedCount,
        @Schema(description = "拒绝的标签数量", example = "0")
        int rejectedCount
) {
}

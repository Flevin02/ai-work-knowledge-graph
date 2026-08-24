package com.flevin.knowgraph.server.model.association;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 文档关系批量审核结果。
 *
 * @param acceptedCount 本次采纳数量
 * @param rejectedCount 本次拒绝数量
 * @param relations 审核后的关系快照
 */
@Schema(description = "文档关系批量审核结果")
public record DocumentRelationReviewBatchResponse(
        @Schema(description = "本次采纳数量", example = "1") int acceptedCount,
        @Schema(description = "本次拒绝数量", example = "1") int rejectedCount,
        @Schema(description = "审核后的关系快照") List<DocumentRelationResponse> relations
) {

    public DocumentRelationReviewBatchResponse {
        relations = List.copyOf(relations);
    }
}

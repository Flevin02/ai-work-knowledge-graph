package com.flevin.knowgraph.server.model.ai;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 一次批量 AI 抽取任务的受理结果。
 *
 * @param requestedCount 本次请求的资料数量
 * @param acceptedCount 已由服务端线程池受理的资料数量
 * @param documentIds 已受理并将独立生成抽取运行记录的来源资料标识
 * @param rejectedDocumentIds 因后台队列繁忙而未受理的来源资料标识
 */
@Schema(description = "批量 AI 抽取任务受理结果")
public record AiExtractionBatchResponse(
        @Schema(description = "本次请求的资料数量", example = "3")
        int requestedCount,
        @Schema(description = "已受理的资料数量", example = "2")
        int acceptedCount,
        @Schema(description = "已受理的来源资料标识")
        List<Long> documentIds,
        @Schema(description = "因后台队列繁忙而未受理的来源资料标识")
        List<Long> rejectedDocumentIds
) {
}

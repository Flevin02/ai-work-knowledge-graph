package com.flevin.knowgraph.server.model.document;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 来源资料最近一次 AI 抽取摘要，不携带模型输出正文。
 *
 * @param extractionId 最近一次抽取记录标识；未开始时为空
 * @param status 最近一次抽取状态；未执行过抽取时为 not_started
 * @param startedAt 最近一次抽取开始时间；未开始时为空
 * @param completedAt 最近一次抽取完成或失败时间；处理中或未开始时为空
 * @param errorMessage 脱敏后的失败摘要；非失败状态时为空
 */
@Schema(description = "来源资料最近一次 AI 抽取摘要")
public record SourceDocumentExtractionSummary(
        @Schema(description = "最近一次抽取记录标识；未开始时为空")
        Long extractionId,
        @Schema(
                description = "最近一次抽取状态",
                allowableValues = {"not_started", "processing", "completed", "failed"},
                example = "completed"
        )
        String status,
        @Schema(description = "最近一次抽取开始时间")
        Instant startedAt,
        @Schema(description = "最近一次抽取完成或失败时间")
        Instant completedAt,
        @Schema(description = "脱敏后的失败摘要")
        String errorMessage
) {

    /**
     * 创建从未执行过 AI 抽取的显式状态。
     *
     * @return not_started 抽取摘要
     */
    public static SourceDocumentExtractionSummary notStarted() {
        return new SourceDocumentExtractionSummary(null, "not_started", null, null, null);
    }
}

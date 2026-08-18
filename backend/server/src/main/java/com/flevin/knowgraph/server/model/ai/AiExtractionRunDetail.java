package com.flevin.knowgraph.server.model.ai;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * AI 抽取运行详情，失败运行也会返回状态和错误摘要。
 *
 * @param summary 抽取运行摘要
 * @param result 完成运行的完整结构化结果；失败运行为空
 */
@Schema(description = "AI 抽取运行详情")
public record AiExtractionRunDetail(
        AiExtractionRunSummary summary,
        AiDocumentExtractionResponse result
) {
}

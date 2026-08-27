package com.flevin.knowgraph.server.model.ai;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * AI 抽取记录列表摘要，不携带完整结果 JSON。
 *
 * @param extractionId 抽取记录标识
 * @param status 抽取状态
 * @param provider 模型协议或供应商
 * @param model 聊天模型名称
 * @param promptVersion Prompt 版本
 * @param schemaVersion Schema 版本
 * @param sectionCount 章节数量
 * @param chunkCount 分片数量
 * @param errorMessage 失败摘要
 * @param createdAt 创建时间
 * @param completedAt 完成时间
 */
@Schema(description = "AI 抽取记录摘要")
public record AiExtractionRunSummary(
        Long extractionId,
        String status,
        String provider,
        String model,
        String promptVersion,
        String schemaVersion,
        int sectionCount,
        int chunkCount,
        String errorMessage,
        Instant createdAt,
        Instant completedAt
) {
}

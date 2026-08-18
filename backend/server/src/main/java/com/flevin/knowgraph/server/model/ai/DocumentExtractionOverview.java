package com.flevin.knowgraph.server.model.ai;

import java.time.Instant;

/**
 * 一份来源资料的最近 AI 抽取概览，用于批量组装资料列表状态。
 *
 * @param documentId 来源资料标识
 * @param extractionId 最近一次抽取记录标识
 * @param status 最近一次抽取状态
 * @param startedAt 最近一次抽取开始时间
 * @param completedAt 最近一次抽取完成或失败时间
 * @param errorMessage 脱敏后的失败摘要
 * @param latestCompletedExtractionId 最近一次成功抽取记录标识；从未成功时为空
 * @param latestCompletedSummary 最近一次成功抽取生成的文档摘要；旧版成功记录可能为空
 */
public record DocumentExtractionOverview(
        String documentId,
        String extractionId,
        String status,
        Instant startedAt,
        Instant completedAt,
        String errorMessage,
        String latestCompletedExtractionId,
        String latestCompletedSummary
) {
}

package com.flevin.knowgraph.server.model.ai;

import java.time.Instant;

/**
 * AI 抽取 SSE 事件协议，事件载荷只描述真实运行阶段和模型输出。
 */
public final class AiExtractionStreamEvents {

    public static final String RUN_STARTED = "run_started";
    public static final String CHUNK_STARTED = "chunk_started";
    public static final String DELTA = "delta";
    public static final String CHUNK_COMPLETED = "chunk_completed";
    public static final String COMPLETED = "completed";
    public static final String ERROR = "error";

    private AiExtractionStreamEvents() {
    }

    /**
     * 抽取运行已经持久化并开始执行。
     */
    public record RunStarted(
            String extractionRunId,
            String documentId,
            String documentName,
            String provider,
            String model,
            String promptVersion,
            String schemaVersion,
            Instant createdAt,
            boolean recoverable
    ) {
    }

    /**
     * 一个来源分片开始调用模型。
     */
    public record ChunkStarted(
            String extractionRunId,
            String documentId,
            String chunkId,
            String sectionPath,
            int chunkIndex,
            int chunkCount,
            Instant occurredAt
    ) {
    }

    /**
     * 供应商真实返回的一段模型原始文本。
     */
    public record Delta(
            String extractionRunId,
            String documentId,
            String chunkId,
            String sectionPath,
            String delta,
            Instant occurredAt
    ) {
    }

    /**
     * 一个来源分片的完整模型输出已经通过结构和证据校验。
     */
    public record ChunkCompleted(
            String extractionRunId,
            String documentId,
            String chunkId,
            String sectionPath,
            int chunkIndex,
            int chunkCount,
            AiChunkExtractionResult chunk,
            Instant occurredAt
    ) {
    }

    /**
     * 整份来源资料已经完成并保存完整候选结果。
     */
    public record Completed(
            String extractionRunId,
            String documentId,
            AiDocumentExtractionResponse result,
            Instant occurredAt
    ) {
    }

    /**
     * 抽取运行失败；只有已持久化的运行才可通过标识恢复失败详情。
     */
    public record Error(
            String extractionRunId,
            String documentId,
            String chunkId,
            String message,
            boolean recoverable,
            Instant occurredAt
    ) {
    }
}

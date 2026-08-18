package com.flevin.knowgraph.server.model.ai;

import com.flevin.knowgraph.server.model.document.SourceDocumentType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * 单份来源资料的 AI 抽取预览响应。
 *
 * @param extractionId 抽取记录标识
 * @param status 抽取运行状态
 * @param createdAt 抽取创建时间
 * @param completedAt 抽取完成时间
 * @param documentId 来源资料标识
 * @param documentName 来源资料名称
 * @param documentType 文档业务类型
 * @param provider 模型协议或供应商
 * @param model 聊天模型名称
 * @param promptVersion Prompt 版本
 * @param schemaVersion 结构化输出 Schema 版本
 * @param sectionCount 解析章节数量
 * @param chunkCount 抽取分片数量
 * @param chunks 按原文顺序返回的分片抽取结果
 */
@Schema(description = "单份来源资料的 AI 抽取预览")
public record AiDocumentExtractionResponse(
        String extractionId,
        String status,
        Instant createdAt,
        Instant completedAt,
        String documentId,
        String documentName,
        SourceDocumentType documentType,
        String provider,
        String model,
        String promptVersion,
        String schemaVersion,
        int sectionCount,
        int chunkCount,
        List<AiChunkExtractionResult> chunks
) {
}

package com.flevin.knowgraph.server.model.document;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 来源资料接口响应，不暴露服务端原始文件路径和完整文本。
 *
 * @param id 来源资料标识
 * @param spaceId 所属知识空间标识
 * @param name 原始文件名
 * @param kind 文件类型
 * @param documentType 文档业务类型
 * @param contentHash SHA-256 内容指纹
 * @param excerpt 最近成功 AI 摘要；尚无可用摘要时为导入原文预览
 * @param status 来源资料状态
 * @param fileSize 文件字节数
 * @param importedAt 首次导入时间
 * @param updatedAt 最近更新时间
 * @param latestExtraction 最近一次 AI 抽取摘要
 * @param latestCompletedExtractionId 最近一次成功抽取记录标识；从未成功时为空
 */
@Schema(description = "已持久化的来源资料摘要")
public record SourceDocumentResponse(
        @Schema(description = "来源资料标识", example = "7ff1d617-997b-46cb-8127-7c99834e57ef")
        Long id,
        @Schema(description = "所属知识空间标识", example = "e5d7b0da-60bd-4e0c-83df-5e7de9509327")
        Long spaceId,
        @Schema(description = "原始文件名", example = "第一次筹备会议纪要.md")
        String name,
        @Schema(description = "文件类型", allowableValues = {"markdown", "txt", "pdf"}, example = "pdf")
        String kind,
        @Schema(description = "文档业务类型", allowableValues = {"general", "prd"}, example = "prd")
        SourceDocumentType documentType,
        @Schema(description = "SHA-256 内容指纹", example = "d7439bee24773b8b381b9f68f45745c2f1222683b7449f1f25c7c7efea20f005")
        String contentHash,
        @Schema(description = "最近成功 AI 摘要；尚无可用摘要时返回导入原文预览", example = "会议确认年会主题和首批行动项。")
        String excerpt,
        @Schema(description = "来源资料状态", example = "active")
        String status,
        @Schema(description = "文件字节数", example = "1024")
        long fileSize,
        @Schema(description = "首次导入时间")
        Instant importedAt,
        @Schema(description = "最近更新时间")
        Instant updatedAt,
        @Schema(description = "最近一次 AI 抽取摘要")
        SourceDocumentExtractionSummary latestExtraction,
        @Schema(description = "最近一次成功抽取记录标识；从未成功时为空")
        String latestCompletedExtractionId
) {
}

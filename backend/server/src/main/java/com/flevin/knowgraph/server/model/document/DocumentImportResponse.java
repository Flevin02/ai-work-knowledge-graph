package com.flevin.knowgraph.server.model.document;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 一次来源资料导入批次的接口响应。
 *
 * @param batchId 导入批次标识
 * @param status 批次最终状态
 * @param totalCount 文件总数
 * @param importedCount 成功导入数
 * @param duplicateCount 重复内容数
 * @param failedCount 处理失败数
 * @param results 各文件处理结果
 */
@Schema(description = "来源资料导入批次结果")
public record DocumentImportResponse(
        @Schema(description = "导入批次标识", example = "7a201ec1-b37c-48da-b388-477817ed0a31")
        Long batchId,
        @Schema(description = "批次状态", example = "completed")
        DocumentImportBatchStatus status,
        @Schema(description = "文件总数", example = "2")
        int totalCount,
        @Schema(description = "成功导入数", example = "1")
        int importedCount,
        @Schema(description = "重复内容数", example = "1")
        int duplicateCount,
        @Schema(description = "处理失败数", example = "0")
        int failedCount,
        @Schema(description = "各文件处理结果")
        List<DocumentImportFileResult> results
) {
}

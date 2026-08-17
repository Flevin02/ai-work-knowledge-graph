package com.flevin.knowgraph.server.model.document;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 单份来源资料的导入结果。
 *
 * @param originalName 原始文件名
 * @param status 导入结果状态
 * @param message 可直接展示的处理说明
 * @param document 新增或已存在的来源资料；失败时为空
 */
@Schema(description = "单份来源资料的导入处理结果")
public record DocumentImportFileResult(
        @Schema(description = "原始文件名", example = "第一次筹备会议纪要.md")
        String originalName,
        @Schema(description = "处理状态", example = "imported")
        DocumentImportFileStatus status,
        @Schema(description = "处理结果说明", example = "来源资料已导入")
        String message,
        @Schema(description = "新增或已存在的来源资料；失败时为空")
        SourceDocumentResponse document
) {
}

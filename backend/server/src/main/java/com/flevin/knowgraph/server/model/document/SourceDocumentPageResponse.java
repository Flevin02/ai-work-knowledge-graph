package com.flevin.knowgraph.server.model.document;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 来源资料服务端分页响应。
 *
 * @param items 当前页来源资料
 * @param page 当前有效页码，从 1 开始
 * @param pageSize 每页数量
 * @param total 当前空间有效来源资料总数
 * @param totalPages 总页数；无资料时为 0
 */
@Schema(description = "来源资料分页结果")
public record SourceDocumentPageResponse(
        @Schema(description = "当前页来源资料")
        List<SourceDocumentResponse> items,
        @Schema(description = "当前有效页码", example = "1")
        int page,
        @Schema(description = "每页数量", example = "12")
        int pageSize,
        @Schema(description = "有效来源资料总数", example = "27")
        long total,
        @Schema(description = "总页数；无资料时为 0", example = "3")
        long totalPages
) {
}

package com.flevin.knowgraph.server.model.document;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 来源资料原文预览响应，不暴露服务端存储路径。
 *
 * @param id 来源资料标识
 * @param spaceId 所属知识空间标识
 * @param name 原始文件名
 * @param kind 文件类型
 * @param documentType 文档业务类型
 * @param contentHash SHA-256 内容指纹
 * @param contentText 服务端解析后的完整文本
 * @param importedAt 首次导入时间
 * @param updatedAt 最近更新时间
 */
@Schema(description = "来源资料原文预览")
public record SourceDocumentContentResponse(
        @Schema(description = "来源资料标识")
        Long id,
        @Schema(description = "所属知识空间标识")
        Long spaceId,
        @Schema(description = "原始文件名")
        String name,
        @Schema(description = "文件类型", allowableValues = {"markdown", "txt", "pdf"}, example = "pdf")
        String kind,
        @Schema(description = "文档业务类型", example = "prd")
        SourceDocumentType documentType,
        @Schema(description = "SHA-256 内容指纹")
        String contentHash,
        @Schema(description = "服务端解析后的完整文本")
        String contentText,
        @Schema(description = "首次导入时间")
        Instant importedAt,
        @Schema(description = "最近更新时间")
        Instant updatedAt
) {
}

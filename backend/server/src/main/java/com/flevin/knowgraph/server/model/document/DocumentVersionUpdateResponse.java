package com.flevin.knowgraph.server.model.document;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 来源资料版本更新结果，即增量导入的变更预览报告。
 *
 * @param status 更新状态：unchanged（内容指纹相同）、dry-run（预览未落库）或 updated
 * @param oldContentHash 更新前的内容指纹；unchanged 时与新指纹相同
 * @param newContentHash 更新后的内容指纹
 * @param staleTagCount 因内容变更被冻结为 stale 的文档标签数量
 * @param staleRelationCount 因内容变更被冻结为 stale 的文档关系数量
 * @param staleVectorCount 因内容变更被冻结为 stale 的分片向量事实数量
 * @param message 面向用户的变更说明
 */
@Schema(description = "来源资料版本更新结果")
public record DocumentVersionUpdateResponse(
        @Schema(description = "更新状态：unchanged、dry-run 或 updated", example = "updated")
        String status,
        @Schema(description = "更新前的内容 SHA-256 指纹")
        String oldContentHash,
        @Schema(description = "更新后的内容 SHA-256 指纹")
        String newContentHash,
        @Schema(description = "被冻结为 stale 的文档标签数量", example = "8")
        int staleTagCount,
        @Schema(description = "被冻结为 stale 的文档关系数量", example = "2")
        int staleRelationCount,
        @Schema(description = "被冻结为 stale 的分片向量事实数量", example = "17")
        int staleVectorCount,
        @Schema(description = "面向用户的变更说明")
        String message
) {
}

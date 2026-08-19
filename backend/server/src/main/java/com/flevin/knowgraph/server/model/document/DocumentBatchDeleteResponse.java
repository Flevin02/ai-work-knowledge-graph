package com.flevin.knowgraph.server.model.document;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 一次来源资料批量软删除的执行结果。
 *
 * @param deletedCount 已软删除的资料数量
 * @param documentIds 已软删除的来源资料标识
 */
@Schema(description = "来源资料批量删除结果")
public record DocumentBatchDeleteResponse(
        @Schema(description = "已软删除的资料数量", example = "2")
        int deletedCount,
        @Schema(description = "已软删除的来源资料标识")
        List<String> documentIds
) {
}

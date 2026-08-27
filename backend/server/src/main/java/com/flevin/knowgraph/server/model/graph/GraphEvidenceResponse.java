package com.flevin.knowgraph.server.model.graph;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 图谱关系证据接口响应。
 *
 * @param sourceDocumentId 来源资料标识
 * @param sourceDocumentName 来源资料名称
 * @param quote 原文证据片段
 * @param locator 原文定位
 * @param extractionMethod 提取方式
 */
@Schema(description = "可追溯到来源资料的关系证据")
public record GraphEvidenceResponse(
        @Schema(description = "来源资料标识")
        Long sourceDocumentId,
        @Schema(description = "来源资料名称", example = "第一次筹备会议纪要.md")
        String sourceDocumentName,
        @Schema(description = "原文证据片段", example = "张三负责场地预订和流程协调。")
        String quote,
        @Schema(description = "原文定位", example = "行动项 1")
        String locator,
        @Schema(description = "提取方式", example = "ai")
        String extractionMethod
) {
}

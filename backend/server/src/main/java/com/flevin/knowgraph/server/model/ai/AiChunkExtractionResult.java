package com.flevin.knowgraph.server.model.ai;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 单个来源分片的 AI 结构化抽取结果。
 *
 * @param chunkId 来源分片标识
 * @param sectionPath 来源章节路径
 * @param extraction 候选实体、关系、证据和冲突
 */
@Schema(description = "单个来源分片的 AI 结构化抽取结果")
public record AiChunkExtractionResult(
        @Schema(description = "来源分片标识")
        String chunkId,
        @Schema(description = "来源章节路径")
        String sectionPath,
        @Schema(description = "结构化候选结果")
        AiExtractionResult extraction
) {
}

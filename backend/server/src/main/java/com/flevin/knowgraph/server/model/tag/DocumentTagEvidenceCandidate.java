package com.flevin.knowgraph.server.model.tag;

import dev.langchain4j.model.output.structured.Description;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 模型输出的一条标签逐字证据候选。
 *
 * @param evidenceId 本次模型输出内的局部证据标识
 * @param sourceDocumentId 证据所在来源资料标识
 * @param chunkId 证据所在分片标识
 * @param sectionPath 证据所在章节路径
 * @param quote 必须能在指定分片逐字反查的原文
 */
@Description("文档标签引用的可定位原文证据")
public record DocumentTagEvidenceCandidate(
        @NotBlank String evidenceId,
        @NotNull Long sourceDocumentId,
        @NotBlank String chunkId,
        @NotNull String sectionPath,
        @NotBlank String quote
) {
}

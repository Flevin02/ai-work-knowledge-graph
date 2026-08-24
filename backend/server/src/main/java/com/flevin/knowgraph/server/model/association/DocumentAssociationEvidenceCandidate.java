package com.flevin.knowgraph.server.model.association;

import dev.langchain4j.model.output.structured.Description;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 文档关联模型返回的原文证据候选。
 *
 * @param evidenceId 本次模型输出内唯一证据标识
 * @param sourceDocumentId 证据所在的当前文档或候选文档标识
 * @param chunkId 证据所在分片标识
 * @param sectionPath 证据所在章节路径
 * @param quote 必须能够在指定分片逐字反查的原文
 */
@Description("文档关系判断引用的可定位原文证据")
public record DocumentAssociationEvidenceCandidate(
        @NotBlank String evidenceId,
        @NotBlank String sourceDocumentId,
        @NotBlank String chunkId,
        @NotNull String sectionPath,
        @NotBlank String quote
) {
}

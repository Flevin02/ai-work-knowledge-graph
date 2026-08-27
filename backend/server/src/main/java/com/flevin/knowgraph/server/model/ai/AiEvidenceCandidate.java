package com.flevin.knowgraph.server.model.ai;

import dev.langchain4j.model.output.structured.Description;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * AI 返回的原文证据候选，服务端仍需反查原文确认。
 *
 * @param evidenceId 本次抽取结果内唯一的证据标识
 * @param sourceDocumentId 来源资料标识
 * @param chunkId 文本分片标识
 * @param sectionPath 章节路径
 * @param quote 来源片段中的逐字引用
 */
@Description("可以在输入原文中逐字找到的证据片段")
public record AiEvidenceCandidate(
        @Description("本次输出内唯一的证据标识，例如 evidence-1")
        @NotBlank String evidenceId,
        @Description("用户输入中提供的来源资料标识")
        @NotNull Long sourceDocumentId,
        @Description("用户输入中提供的文本分片标识")
        @NotBlank String chunkId,
        @Description("用户输入中提供的章节路径")
        @NotBlank String sectionPath,
        @Description("必须能够在输入原文中逐字找到的短引用")
        @NotBlank @Size(max = 1000) String quote
) {
}

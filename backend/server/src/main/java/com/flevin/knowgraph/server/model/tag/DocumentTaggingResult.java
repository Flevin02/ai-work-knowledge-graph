package com.flevin.knowgraph.server.model.tag;

import dev.langchain4j.model.output.structured.Description;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 标签客户端返回的 document-tag-v1 完整结构化结果。
 *
 * @param summary 文档自然摘要
 * @param tags 最多八条标签候选
 * @param evidences 本次输出声明的全部逐字证据
 */
@Description("文档摘要、标签候选和逐字证据")
public record DocumentTaggingResult(
        @NotBlank @Size(max = 160) String summary,
        @NotNull @Size(max = 8) List<@Valid DocumentTagCandidate> tags,
        @NotNull List<@Valid DocumentTagEvidenceCandidate> evidences
) {

    public DocumentTaggingResult {
        tags = tags == null ? null : List.copyOf(tags);
        evidences = evidences == null ? null : List.copyOf(evidences);
    }
}

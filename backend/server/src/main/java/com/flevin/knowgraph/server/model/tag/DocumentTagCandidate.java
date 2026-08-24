package com.flevin.knowgraph.server.model.tag;

import dev.langchain4j.model.output.structured.Description;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 模型输出的一条标签候选。
 *
 * @param candidateId 本次模型输出内的局部候选标识
 * @param name 标签展示名称
 * @param confidence 模型置信度，仅供人工审核参考
 * @param evidenceIds 支撑当前标签的模型局部证据标识
 */
@Description("一条带逐字证据引用的文档标签候选")
public record DocumentTagCandidate(
        @NotBlank String candidateId,
        @NotBlank @Size(min = 2, max = 24) String name,
        @DecimalMin("0.0") @DecimalMax("1.0") double confidence,
        @NotNull @Size(min = 1) List<@NotBlank String> evidenceIds
) {

    public DocumentTagCandidate {
        evidenceIds = evidenceIds == null ? null : List.copyOf(evidenceIds);
    }
}

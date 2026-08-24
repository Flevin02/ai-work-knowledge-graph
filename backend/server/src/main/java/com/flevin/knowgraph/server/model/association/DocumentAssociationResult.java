package com.flevin.knowgraph.server.model.association;

import dev.langchain4j.model.output.structured.Description;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 文档关联判断客户端返回的完整结构化结果。
 *
 * @param evidences 本次输出声明的全部证据候选
 * @param decisions 与服务端候选集合一一对应的关系判断
 */
@Description("服务端候选文档的关系判断和原文证据")
public record DocumentAssociationResult(
        @NotNull List<@Valid DocumentAssociationEvidenceCandidate> evidences,
        @NotNull List<@Valid DocumentAssociationDecision> decisions
) {

    public DocumentAssociationResult {
        evidences = evidences == null ? null : List.copyOf(evidences);
        decisions = decisions == null ? null : List.copyOf(decisions);
    }
}

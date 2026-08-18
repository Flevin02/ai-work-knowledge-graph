package com.flevin.knowgraph.server.model.ai;

import dev.langchain4j.model.output.structured.Description;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * AI 对单个来源分片返回的完整结构化候选结果。
 *
 * @param summary 当前来源分片的简短摘要
 * @param entities 候选实体
 * @param relations 候选关系
 * @param evidences 原文证据候选
 * @param conflicts 明确的信息冲突
 */
@Description("来源分片的摘要及结构化实体、关系、证据和冲突候选结果")
public record AiExtractionResult(
        @Description("只根据当前来源分片生成的简短摘要，不得补充原文外事实")
        @NotBlank(message = "分片摘要不能为空")
        @Size(max = 160, message = "分片摘要不能超过 160 个字符")
        String summary,
        @Description("候选实体列表；没有实体时返回空数组")
        @NotNull List<@Valid AiEntityCandidate> entities,
        @Description("候选关系列表；没有关系时返回空数组")
        @NotNull List<@Valid AiRelationCandidate> relations,
        @Description("原文证据列表；没有证据时返回空数组")
        @NotNull List<@Valid AiEvidenceCandidate> evidences,
        @Description("明确冲突列表；没有冲突时返回空数组")
        @NotNull List<@Valid AiConflictCandidate> conflicts
) {
}

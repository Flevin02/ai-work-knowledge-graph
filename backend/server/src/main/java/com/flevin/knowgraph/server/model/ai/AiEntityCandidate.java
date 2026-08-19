package com.flevin.knowgraph.server.model.ai;

import dev.langchain4j.model.output.structured.Description;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * AI 从来源片段中识别出的候选实体。
 *
 * @param candidateId 本次抽取结果内唯一的临时候选标识
 * @param type 固定实体类型
 * @param name 原文中的实体名称
 * @param summary 仅基于证据生成的零至 160 个字符实体摘要
 * @param evidenceIds 支撑实体判断的证据标识
 */
@Description("来源文档中的候选实体；只有原文明确支持时才能输出")
public record AiEntityCandidate(
        @Description("本次输出内唯一的临时候选标识，例如 entity-1")
        @NotBlank String candidateId,
        @Description("实体类型")
        @NotNull AiEntityType type,
        @Description("原文中的实体名称")
        @NotBlank @Size(max = 200) String name,
        @Description("只根据原文生成的实体摘要，长度为零至 160 个字符；中文、字母、数字、空格和标点均计入长度；原文不足时允许返回空值")
        @Size(max = 160, message = "实体摘要不能超过 160 个字符") String summary,
        @Description("支撑该实体的证据标识列表")
        @NotNull @Size(min = 1) List<@NotBlank String> evidenceIds
) {
}

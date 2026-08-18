package com.flevin.knowgraph.server.model.ai;

import dev.langchain4j.model.output.structured.Description;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * AI 识别出的候选实体关系。
 *
 * @param sourceEntityId 主体候选实体标识
 * @param targetEntityId 客体候选实体标识
 * @param relationType 稳定的小写关系类型
 * @param confidence 模型对候选关系的估计置信度
 * @param evidenceIds 直接支撑该关系的证据标识
 */
@Description("来源文档明确支持的候选关系；关系方向必须与原文一致")
public record AiRelationCandidate(
        @Description("主体候选实体标识")
        @NotBlank String sourceEntityId,
        @Description("客体候选实体标识")
        @NotBlank String targetEntityId,
        @Description("小写下划线关系类型，例如 feature_contains_requirement")
        @NotBlank @Pattern(regexp = "^[a-z][a-z0-9_]*$") String relationType,
        @Description("零到一之间的候选置信度；不是人工审核结果")
        @DecimalMin("0.0") @DecimalMax("1.0") double confidence,
        @Description("直接支撑该关系的证据标识列表")
        @NotNull @Size(min = 1) List<@NotBlank String> evidenceIds
) {
}

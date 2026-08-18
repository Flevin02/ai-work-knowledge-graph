package com.flevin.knowgraph.server.model.ai;

import dev.langchain4j.model.output.structured.Description;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 同一输入上下文中可以被证据支持的信息冲突。
 *
 * @param conflictType 稳定的小写冲突类型
 * @param description 冲突说明
 * @param evidenceIds 支撑冲突判断的证据标识
 */
@Description("输入原文中明确出现的信息冲突；没有冲突时不要输出")
public record AiConflictCandidate(
        @Description("小写下划线冲突类型")
        @NotBlank @Pattern(regexp = "^[a-z][a-z0-9_]*$") String conflictType,
        @Description("只根据证据描述的冲突内容")
        @NotBlank @Size(max = 1000) String description,
        @Description("支撑冲突判断的证据标识列表")
        @NotNull @Size(min = 1) List<@NotBlank String> evidenceIds
) {
}

package com.flevin.knowgraph.server.model.association;

import dev.langchain4j.model.output.structured.Description;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 模型对一份服务端候选文档作出的唯一关联判断。
 *
 * @param candidateDocumentId 服务端候选文档标识
 * @param relationType 五种白名单关系或 none
 * @param direction 当前文档与候选文档之间的方向
 * @param confidence 模型置信度，仅供审核参考
 * @param reason 不超过 200 字的判断原因
 * @param matchedTagIds 命中的服务端标签；阶段 1 必须为空
 * @param evidenceIds 本条判断引用的证据标识
 */
@Description("一份服务端候选文档的关系判断")
public record DocumentAssociationDecision(
        @NotBlank String candidateDocumentId,
        @NotBlank String relationType,
        @NotBlank String direction,
        @DecimalMin("0.0") @DecimalMax("1.0") double confidence,
        @NotBlank @Size(max = 200) String reason,
        @NotNull List<@NotBlank String> matchedTagIds,
        @NotNull List<@NotBlank String> evidenceIds
) {

    public DocumentAssociationDecision {
        matchedTagIds = matchedTagIds == null ? null : List.copyOf(matchedTagIds);
        evidenceIds = evidenceIds == null ? null : List.copyOf(evidenceIds);
    }
}

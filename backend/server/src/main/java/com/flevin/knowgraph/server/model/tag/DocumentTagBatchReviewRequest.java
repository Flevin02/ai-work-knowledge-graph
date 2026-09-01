package com.flevin.knowgraph.server.model.tag;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 跨文档批量审核请求：对所选资料的 suggested 标签执行统一审核动作。
 *
 * @param documentIds 待审核的来源资料标识
 * @param action 审核动作：accept 或 reject
 * @param reason 可选审核说明
 */
@Schema(description = "跨文档批量审核文档标签请求")
public record DocumentTagBatchReviewRequest(
        @Schema(description = "待审核的来源资料标识，最多 12 份")
        @NotEmpty(message = "请至少选择一份来源资料")
        @Size(max = 12, message = "单次最多操作 12 份来源资料")
        List<Long> documentIds,
        @Schema(description = "审核动作：accept 或 reject", example = "accept")
        @NotBlank(message = "审核动作不能为空")
        @Pattern(regexp = "accept|reject", message = "审核动作只支持 accept 或 reject")
        String action,
        @Schema(description = "可选审核说明")
        @Size(max = 500, message = "审核说明不能超过 500 字")
        String reason
) {

    public DocumentTagBatchReviewRequest {
        documentIds = documentIds == null ? null : List.copyOf(documentIds);
    }
}

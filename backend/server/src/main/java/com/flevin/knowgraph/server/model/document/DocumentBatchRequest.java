package com.flevin.knowgraph.server.model.document;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 一次针对多份来源资料的批量操作请求。
 *
 * @param documentIds 当前知识空间内待操作的来源资料标识；同一批次不允许重复
 */
@Schema(description = "来源资料批量操作请求")
public record DocumentBatchRequest(
        @Schema(description = "待操作的来源资料标识，最多 12 条", example = "[\"document-1\", \"document-2\"]")
        @NotEmpty(message = "请至少选择一份来源资料")
        @Size(max = 12, message = "单次最多操作 12 份来源资料")
        List<@NotBlank(message = "来源资料标识不能为空") @Size(max = 100, message = "来源资料标识长度不能超过 100") String> documentIds
) {
}

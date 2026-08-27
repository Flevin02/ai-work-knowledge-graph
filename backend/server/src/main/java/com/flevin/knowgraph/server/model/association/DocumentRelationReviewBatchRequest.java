package com.flevin.knowgraph.server.model.association;

import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 文档关系批量审核请求。
 *
 * @param reviews 服务端关系标识和采纳/拒绝动作
 */
@Schema(description = "文档关系批量审核请求")
public record DocumentRelationReviewBatchRequest(
        @Schema(description = "审核决定，至少一条")
        @NotEmpty @Size(max = 100) List<@Valid Item> reviews
) {

    public DocumentRelationReviewBatchRequest {
        reviews = reviews == null ? null : List.copyOf(reviews);
    }

    /**
     * 单条文档关系审核决定。
     *
     * @param relationId 服务端文档关系标识
     * @param action 采纳或拒绝
     * @param reason 可选审核说明
     */
    @Schema(description = "单条文档关系审核决定")
    public record Item(
            @Schema(description = "服务端文档关系标识") @NotNull Long relationId,
            @Schema(description = "审核动作") @NotNull Action action,
            @Schema(description = "审核说明") @Size(max = 500) String reason
    ) {
    }

    /**
     * 文档关系审核动作。
     */
    public enum Action {
        ACCEPT("accept"),
        REJECT("reject");

        private final String value;

        Action(String value) {
            this.value = value;
        }

        /**
         * 获取数据库和 API 使用的小写动作值。
         *
         * @return 小写审核动作
         */
        @JsonValue
        public String getValue() {
            return value;
        }
    }
}

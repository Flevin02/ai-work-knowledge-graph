package com.flevin.knowgraph.server.model.document;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 来源资料导入批次的最终状态。
 */
public enum DocumentImportBatchStatus {

    PROCESSING("processing"),
    COMPLETED("completed"),
    PARTIAL_FAILED("partial_failed"),
    FAILED("failed");

    private final String value;

    DocumentImportBatchStatus(String value) {
        this.value = value;
    }

    /**
     * 获取数据库和前后端契约使用的状态值。
     *
     * @return 小写状态值
     */
    @JsonValue
    public String getValue() {
        return value;
    }
}

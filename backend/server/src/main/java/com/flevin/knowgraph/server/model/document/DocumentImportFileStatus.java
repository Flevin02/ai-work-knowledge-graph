package com.flevin.knowgraph.server.model.document;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 单份来源资料的导入结果状态。
 */
public enum DocumentImportFileStatus {

    IMPORTED("imported"),
    DUPLICATE("duplicate"),
    FAILED("failed");

    private final String value;

    DocumentImportFileStatus(String value) {
        this.value = value;
    }

    /**
     * 获取前后端契约使用的状态值。
     *
     * @return 小写状态值
     */
    @JsonValue
    public String getValue() {
        return value;
    }
}

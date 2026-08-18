package com.flevin.knowgraph.server.model.document;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.Optional;

/**
 * 来源资料的业务语义类型，与 Markdown/TXT 等文件格式相互独立。
 */
public enum SourceDocumentType {

    GENERAL("general"),
    PRD("prd");

    private final String value;

    SourceDocumentType(String value) {
        this.value = value;
    }

    /**
     * 获取前后端和数据库使用的小写业务类型值。
     *
     * @return 小写业务类型值
     */
    @JsonValue
    public String getValue() {
        return value;
    }

    /**
     * 将接口或数据库中的文本解析为业务类型。
     *
     * @param value 待解析文本
     * @return 匹配的业务类型；空值或未知值返回空
     */
    public static Optional<SourceDocumentType> fromValue(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }

        // 忽略大小写匹配稳定的业务类型值
        return Arrays.stream(values())
                .filter(type -> type.value.equalsIgnoreCase(value.strip()))
                .findFirst();
    }
}

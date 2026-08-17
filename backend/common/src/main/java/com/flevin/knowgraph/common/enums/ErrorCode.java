package com.flevin.knowgraph.common.enums;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 系统通用错误码。
 */
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public enum ErrorCode {

    SUCCESS(200, "操作成功"),
    PARAM_ERROR(400, "参数错误"),
    PARAM_VALID_ERROR(400, "参数校验失败"),
    BUSINESS_ERROR(400, "业务处理失败"),
    NOT_FOUND(404, "请求的资源不存在"),
    DATA_ALREADY_EXISTS(409, "数据已存在"),
    DATABASE_ERROR(500, "数据库操作失败"),
    SYSTEM_ERROR(500, "系统内部错误，请稍后重试"),
    AI_SERVICE_UNAVAILABLE(503, "AI 服务暂时不可用，请稍后重试");

    private final int code;
    private final String message;
}

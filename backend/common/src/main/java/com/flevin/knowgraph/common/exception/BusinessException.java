package com.flevin.knowgraph.common.exception;

import com.flevin.knowgraph.common.enums.ErrorCode;
import lombok.Getter;

/**
 * 业务异常，用于记录需要保留异常上下文的业务处理失败。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    /**
     * 根据错误码创建业务异常。
     *
     * @param errorCode 错误码
     */
    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /**
     * 根据错误码、业务消息和原因创建业务异常。
     *
     * @param errorCode 错误码
     * @param message 业务消息
     * @param cause 原始异常
     */
    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }
}

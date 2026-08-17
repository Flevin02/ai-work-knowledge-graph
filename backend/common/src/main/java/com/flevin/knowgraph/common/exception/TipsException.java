package com.flevin.knowgraph.common.exception;

import com.flevin.knowgraph.common.enums.ErrorCode;
import lombok.Getter;

/**
 * 提示异常，用于向前端返回可直接展示的业务提示。
 */
@Getter
public class TipsException extends RuntimeException {

    private final ErrorCode errorCode;

    /**
     * 根据错误码和提示消息创建异常。
     *
     * @param errorCode 错误码
     * @param message 提示消息
     */
    public TipsException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}

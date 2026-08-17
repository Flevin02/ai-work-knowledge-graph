package com.flevin.knowgraph.common.model;

import com.flevin.knowgraph.common.context.TraceContext;
import com.flevin.knowgraph.common.enums.ErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 统一 API 响应对象，用于向前端返回一致的状态、消息、链路标识和业务数据。
 *
 * @param <T> 响应数据类型
 */
@Data
@NoArgsConstructor
@Schema(description = "知脉统一 API 响应包装对象")
public class ApiResponse<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "是否发生错误", example = "false")
    private boolean error;

    @Schema(description = "业务响应码", example = "200")
    private int code;

    @Schema(description = "响应消息", example = "操作成功")
    private String msg;

    @Schema(description = "响应时间戳，单位为毫秒", example = "1786956926812")
    private Long timestamp;

    @Schema(description = "请求链路标识，可用于关联后端日志", example = "2089274896406102016")
    private String traceId;

    @Schema(description = "业务响应数据")
    private T data;

    /**
     * 创建统一 API 响应。
     *
     * @param error 是否发生错误
     * @param code 响应状态码
     * @param msg 响应消息
     * @param data 响应数据
     */
    public ApiResponse(boolean error, int code, String msg, T data) {
        this.error = error;
        this.code = code;
        this.msg = msg;
        this.data = data;
        this.timestamp = System.currentTimeMillis();

        // 获取当前请求链路标识，便于前后端共同定位问题
        this.traceId = TraceContext.getTraceId();
    }

    /**
     * 创建带数据的成功响应。
     *
     * @param data 响应数据
     * @param <T> 响应数据类型
     * @return 成功响应
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(false, ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), data);
    }

    /**
     * 根据错误码创建失败响应。
     *
     * @param errorCode 错误码
     * @param <T> 响应数据类型
     * @return 失败响应
     */
    public static <T> ApiResponse<T> error(ErrorCode errorCode) {
        return new ApiResponse<>(true, errorCode.getCode(), errorCode.getMessage(), null);
    }

    /**
     * 根据错误码和业务消息创建失败响应。
     *
     * @param errorCode 错误码
     * @param message 业务消息
     * @param <T> 响应数据类型
     * @return 失败响应
     */
    public static <T> ApiResponse<T> error(ErrorCode errorCode, String message) {
        return new ApiResponse<>(true, errorCode.getCode(), message, null);
    }
}

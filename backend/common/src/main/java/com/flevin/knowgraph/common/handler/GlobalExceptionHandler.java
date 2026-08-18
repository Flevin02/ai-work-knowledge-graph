package com.flevin.knowgraph.common.handler;

import com.flevin.knowgraph.common.enums.ErrorCode;
import com.flevin.knowgraph.common.exception.BusinessException;
import com.flevin.knowgraph.common.exception.TipsException;
import com.flevin.knowgraph.common.model.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器，统一向前端返回可追踪的错误响应。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String FAVICON_RESOURCE_PATH = "favicon.ico";

    /**
     * 处理可直接展示给用户的提示异常。
     *
     * @param exception 提示异常
     * @return 失败响应
     */
    @ExceptionHandler(TipsException.class)
    public ResponseEntity<ApiResponse<Void>> handleTipsException(TipsException exception) {
        log.warn("业务提示: code={}, message={}", exception.getErrorCode().getCode(), exception.getMessage());

        // 将业务错误码映射为一致的 RESTful HTTP 状态
        HttpStatus status = resolveHttpStatus(exception.getErrorCode());

        // 使用统一响应体返回可直接展示的业务提示
        return ResponseEntity.status(status)
                .body(ApiResponse.error(exception.getErrorCode(), exception.getMessage()));
    }

    /**
     * 处理需要保留异常上下文的业务异常。
     *
     * @param exception 业务异常
     * @return 失败响应
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
        log.error("业务异常: code={}, message={}", exception.getErrorCode().getCode(), exception.getMessage(), exception);

        // 将业务错误码映射为一致的 RESTful HTTP 状态
        HttpStatus status = resolveHttpStatus(exception.getErrorCode());

        // 使用统一响应体返回带上下文的业务异常
        return ResponseEntity.status(status)
                .body(ApiResponse.error(exception.getErrorCode(), exception.getMessage()));
    }

    /**
     * 处理请求参数校验失败。
     *
     * @param exception 参数校验异常
     * @return 失败响应
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleValidationException(MethodArgumentNotValidException exception) {
        // 汇总全部字段校验消息，便于前端一次性展示
        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败: {}", message);
        return ApiResponse.error(ErrorCode.PARAM_VALID_ERROR, message);
    }

    /**
     * 处理静态资源或接口路径不存在异常。
     *
     * @param exception 资源不存在异常
     * @return 资源不存在响应
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleNoResourceFoundException(NoResourceFoundException exception) {
        if (FAVICON_RESOURCE_PATH.equals(exception.getResourcePath())) {
            log.debug("浏览器请求 favicon.ico，但当前服务未配置站点图标");
        } else {
            log.warn("请求资源不存在: {}", exception.getResourcePath());
        }
        return ApiResponse.error(ErrorCode.NOT_FOUND);
    }

    /**
     * 处理未被业务层捕获的系统异常。
     *
     * @param exception 系统异常
     * @return 失败响应
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleException(Exception exception) {
        log.error("系统异常: {}", exception.getMessage(), exception);
        return ApiResponse.error(ErrorCode.SYSTEM_ERROR);
    }

    /**
     * 将项目错误码转换为对应 HTTP 状态，未知状态回退为 400。
     *
     * @param errorCode 项目错误码
     * @return RESTful HTTP 状态
     */
    private HttpStatus resolveHttpStatus(ErrorCode errorCode) {
        // 使用错误码数值解析标准 HTTP 状态
        HttpStatus status = HttpStatus.resolve(errorCode.getCode());
        return status == null ? HttpStatus.BAD_REQUEST : status;
    }
}

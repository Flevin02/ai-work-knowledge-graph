package com.flevin.knowgraph.common.filter;

import com.flevin.knowgraph.common.context.TraceContext;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * HTTP 链路追踪过滤器，负责接收或生成 traceId，并写入响应头。
 */
public class TraceIdFilter implements Filter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        try {
            String traceId = httpRequest.getHeader(TRACE_ID_HEADER);
            if (traceId != null && !traceId.isBlank()) {
                // 复用调用方传入的链路标识
                TraceContext.setTraceId(traceId);
            }

            // 将当前链路标识返回给调用方，便于前后端联合排查
            httpResponse.setHeader(TRACE_ID_HEADER, TraceContext.getTraceId());

            // 继续执行后续过滤器和业务接口
            chain.doFilter(request, response);
        } finally {
            // 请求结束后清理线程上下文，避免容器线程复用导致数据串联
            TraceContext.clear();
        }
    }
}

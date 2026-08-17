package com.flevin.knowgraph.common.context;

import cn.hutool.core.util.IdUtil;
import com.alibaba.ttl.TransmittableThreadLocal;
import org.slf4j.MDC;

/**
 * 请求链路上下文，负责生成、传递和清理 traceId。
 */
public final class TraceContext {

    private static final String TRACE_ID_KEY = "traceId";
    private static final TransmittableThreadLocal<String> TRACE_ID_HOLDER = new TransmittableThreadLocal<>();

    private TraceContext() {
    }

    /**
     * 获取当前 traceId；不存在时自动生成。
     *
     * @return 当前 traceId
     */
    public static String getTraceId() {
        String traceId = TRACE_ID_HOLDER.get();
        if (traceId == null || traceId.isBlank()) {
            // 生成当前请求唯一链路标识
            traceId = IdUtil.getSnowflakeNextIdStr();

            // 保存链路标识到当前线程上下文
            setTraceId(traceId);
        }
        return traceId;
    }

    /**
     * 设置当前 traceId。
     *
     * @param traceId 外部传入或新生成的 traceId
     */
    public static void setTraceId(String traceId) {
        TRACE_ID_HOLDER.set(traceId);

        // 同步到日志 MDC，保证日志格式可以输出链路标识
        MDC.put(TRACE_ID_KEY, traceId);
    }

    /**
     * 清理当前请求链路上下文。
     */
    public static void clear() {
        TRACE_ID_HOLDER.remove();

        // 清理 MDC，避免容器线程复用导致链路数据串联
        MDC.remove(TRACE_ID_KEY);
    }
}

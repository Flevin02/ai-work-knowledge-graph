package com.flevin.knowgraph.server.service.ai;

/**
 * AI 抽取运行事件发布边界，用于隔离业务编排与 SSE 等具体传输协议。
 */
@FunctionalInterface
public interface AiExtractionEventPublisher {

    /**
     * 发布一条具有稳定事件名称的抽取运行事件。
     *
     * @param eventName 稳定事件名称
     * @param payload 可由传输层序列化的事件载荷
     */
    void publish(String eventName, Object payload);
}

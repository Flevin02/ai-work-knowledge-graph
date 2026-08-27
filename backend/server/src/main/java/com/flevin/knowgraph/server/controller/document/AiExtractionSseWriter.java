package com.flevin.knowgraph.server.controller.document;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flevin.knowgraph.server.service.ai.AiExtractionEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 抽取 SSE 写出器，把领域运行事件编码为浏览器可增量消费的文本事件流。
 */
@Slf4j
@Component
public class AiExtractionSseWriter {

    private final ObjectMapper objectMapper;

    public AiExtractionSseWriter(
            @Qualifier("apiObjectMapper") ObjectMapper objectMapper
    ) {
        this.objectMapper = objectMapper;
    }

    /**
     * 为单次 HTTP 响应创建事件发布器；客户端断开后停止写出，但不取消后台抽取。
     *
     * @param outputStream 当前 SSE 响应输出流
     * @return 与当前连接绑定的事件发布器
     */
    public AiExtractionEventPublisher createPublisher(OutputStream outputStream) {
        AtomicBoolean connected = new AtomicBoolean(true);
        return (eventName, payload) -> {
            if (!connected.get()) {
                return;
            }

            String payloadJson;
            try {
                // 使用服务端统一 Jackson 配置序列化事件载荷，避免手工拼接 JSON
                payloadJson = objectMapper.writeValueAsString(payload);
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("无法序列化 AI 抽取流事件", exception);
            }

            String eventText = "event:" + eventName + "\n"
                    + "data:" + payloadJson + "\n\n";
            try {
                // 写出一条完整 SSE 事件并立即刷新，避免代理前的服务端缓冲
                outputStream.write(eventText.getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
            } catch (IOException exception) {
                connected.set(false);
                log.debug("AI 抽取 SSE 客户端已断开: eventName={}", eventName, exception);
            }
        };
    }
}

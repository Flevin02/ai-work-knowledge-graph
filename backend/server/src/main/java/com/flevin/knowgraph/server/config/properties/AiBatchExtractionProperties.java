package com.flevin.knowgraph.server.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 批量 AI 抽取后台线程池配置，限制同时访问模型和 SQLite 写入收口的任务数量。
 */
@Data
@ConfigurationProperties(prefix = "ai.batch-extraction")
public class AiBatchExtractionProperties {

    /**
     * 同时运行的来源资料抽取任务数量。
     */
    private int maxConcurrency = 2;

    /**
     * 等待后台线程执行的批量抽取任务数量上限。
     */
    private int queueCapacity = 12;
}

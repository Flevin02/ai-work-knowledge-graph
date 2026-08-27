package com.flevin.knowgraph.server.config;

import com.flevin.knowgraph.server.config.properties.AiBatchExtractionProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 批量 AI 抽取任务执行器配置，避免一次多选提取无限并发占满模型和 MySQL 资源。
 */
@Configuration
@RequiredArgsConstructor
public class AiBatchExtractionExecutorConfiguration {

    private final AiBatchExtractionProperties batchExtractionProperties;

    /**
     * 创建用于批量来源资料抽取的有界后台线程池。
     *
     * @return 只服务于批量 AI 抽取任务的线程池
     */
    @Bean(name = "aiBatchExtractionExecutor")
    public ThreadPoolTaskExecutor aiBatchExtractionExecutor() {
        int maxConcurrency = Math.max(1, batchExtractionProperties.getMaxConcurrency());
        ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setCorePoolSize(maxConcurrency);
        taskExecutor.setMaxPoolSize(maxConcurrency);
        taskExecutor.setQueueCapacity(Math.max(0, batchExtractionProperties.getQueueCapacity()));
        taskExecutor.setThreadNamePrefix("ai-batch-extraction-");
        taskExecutor.setWaitForTasksToCompleteOnShutdown(true);
        taskExecutor.setAwaitTerminationSeconds(30);

        // 初始化线程池，使批量接口可立即向受控队列提交后台抽取任务
        taskExecutor.initialize();
        return taskExecutor;
    }
}

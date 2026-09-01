package com.flevin.fixture;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 有据问答集成测试的 Fake 客户端配置。
 *
 * <p>位于应用组件扫描包之外，仅由显式 {@code @Import} 的测试上下文加载，
 * 避免泄漏进依赖“客户端未启用”降级路径的其他测试。</p>
 */
@Configuration
public class ConversationAnswerFakeConfiguration {

    /**
     * 提供可编程的问答客户端桩。
     *
     * @return 测试专用问答客户端
     */
    @Bean
    ConversationAnswerClientStub conversationAnswerClientStub() {
        return new ConversationAnswerClientStub();
    }
}

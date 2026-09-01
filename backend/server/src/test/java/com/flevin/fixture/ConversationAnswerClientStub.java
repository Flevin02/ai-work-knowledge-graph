package com.flevin.fixture;

import com.flevin.knowgraph.server.model.conversation.ConversationAnswerRequest;
import com.flevin.knowgraph.server.model.conversation.ConversationAnswerResult;
import com.flevin.knowgraph.server.service.conversation.ConversationAnswerClient;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 集成测试用的可编程问答客户端桩。
 *
 * <p>放在 {@code com.flevin.fixture} 而不是应用扫描包内：应用主类以
 * {@code @ComponentScan("com.flevin.knowgraph")} 扫描类路径，测试类若带
 * {@code @TestConfiguration}（元注解为 {@code @Component}）落在扫描包内，
 * 会被注册进每一个测试上下文，污染“客户端未启用”等降级断言。本包位于
 * 扫描范围之外，由需要的测试通过 {@code @Import} 显式引入。</p>
 *
 * <p>默认无可用回答：未通过 {@link #nextResult} 预设结果时调用将抛出
 * 运行时异常，用于验证服务端对客户端失败的降级路径。</p>
 */
public class ConversationAnswerClientStub implements ConversationAnswerClient {

    /** 用例预设的下一次回答结果；为 null 时客户端按不可用处理。 */
    public final AtomicReference<ConversationAnswerResult> nextResult = new AtomicReference<>();

    @Override
    public String clientId() {
        return "stub";
    }

    @Override
    public ConversationAnswerResult answer(ConversationAnswerRequest request) {
        ConversationAnswerResult result = nextResult.get();
        if (result == null) {
            throw new IllegalStateException("测试桩未预设回答结果");
        }
        return result;
    }
}

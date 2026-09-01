# 有据问答生产客户端 v1 验证报告

验证日期：2026-09-01

## 1. 验证对象

- OpenAI-compatible `ConversationAnswerClient` 的限定上下文渲染和结构化结果映射。
- `ai.enabled=true` 时的 Spring Bean 装配，以及关闭时的未启用降级。
- 非法结构、模型异常、无效引用和用户消息保留的失败边界。
- 既有 Fake/MySQL/MockMvc 问答与项目全量回归。

## 2. TDD 证据

实现前依次观察到以下预期失败：

1. 生产客户端类不存在，适配器测试无法编译。
2. AI 配置中没有 `ConversationAnswerClient` Bean，Spring 测试因依赖缺失失败。
3. 空回答没有被拒绝，非法输出领域异常不存在。
4. 非 JSON 输出直接泄漏 LangChain4j `OutputParsingException`。
5. 业务层把非法输出错误归为通用 `answer_failed`。

每个失败均只补最小生产实现后重新运行并转为通过。

## 3. 针对性验证

执行：

```bash
cd backend
kg_java_home=$(/usr/libexec/java_home -v 21)
JAVA_HOME="$kg_java_home" PATH="$kg_java_home/bin:$PATH" \
  mvn -pl server -am \
  -Dtest=OpenAiCompatibleConversationAnswerClientTests,ConversationIntegrationTests,AiConfigurationIntegrationTests,ConversationClientUnavailableIntegrationTests \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：15 项测试，0 失败，0 错误，0 跳过。

覆盖结论：

- 合法 JSON 映射为回答和候选引用，模型输入只包含问题与服务端限定分片。
- 非 JSON 和空回答归为 `answer_invalid_output`；普通模型异常保持 `answer_failed`。
- AI 关闭时仍为 `answer_client_unavailable`，不会创建生产客户端或发起外部调用。
- 无效引用不落库；非法输出和模型异常均不丢失已提交的用户消息。

## 4. Java 21 根 Reactor 回归

执行：

```bash
cd backend
kg_java_home=$(/usr/libexec/java_home -v 21)
JAVA_HOME="$kg_java_home" PATH="$kg_java_home/bin:$PATH" mvn -pl server -am test
```

Maven 完整测试汇总：139 项测试，0 失败，0 错误，0 跳过。

该结果证明当前源码可在 Java 21 下编译，并通过 Fake、Mock、MockMvc 和本机 MySQL 自动回归。测试中的 `ChatModel` 使用 Mock，配置装配测试只创建客户端而不调用远程模型。

## 5. 未覆盖边界

- 未执行 `real-ai` Profile，未证明当前 API Key、Base URL、模型权限、供应商协议兼容性、真实结构化输出质量、费用或延迟。
- 未实现或验证问答前端、浏览器交互、引用点击定位、SSE、断线恢复和生产部署。
- 未引入跨文档检索、Milvus、Reranker 或 Agent；无文档范围的会话仍按空上下文处理。

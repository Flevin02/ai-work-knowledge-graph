# 有据问答生产客户端 v1 技术设计

## 1. 设计范围

本设计只覆盖 `ConversationAnswerClient` 的 OpenAI-compatible 生产适配，以及模型输出进入既有 `ConversationService` 后的失败分类。它不改变会话、消息、引用表和 HTTP 资源，不负责检索、引用校验、前端交互或长耗时恢复。

## 2. 依赖方向

```text
ConversationService
  -> ConversationAnswerClient（领域接口）
      -> OpenAiCompatibleConversationAnswerClient
          -> LangChain4j AI Service
              -> ChatModel

ConversationService
  -> 分片归属校验 / quote 与 offset 逐字反查
  -> groundingStatus 判定
  -> 回答与引用原子持久化
```

业务层只依赖 `ConversationAnswerClient`、`ConversationAnswerRequest` 和 `ConversationAnswerResult`，不接触 LangChain4j、Prompt 注解或具体 OpenAI-compatible 类型。生产适配器不访问 Repository，也不拥有知识空间隔离和事实持久化职责。

## 3. 输入与上下文

`ConversationAnswerRequest` 包含：

- `question`：已经通过接口参数校验的用户问题。
- `contextChunks`：服务端限定的章节感知分片，每个分片只暴露局部 `chunkId`、`sectionPath` 和原文。

适配器按服务端给定顺序渲染问题和分片，不传递数据库记录 ID、文件路径、API Key 或其他知识空间资料。当前 `ConversationService` 只为带 `scopeDocumentId` 的会话组装范围文档分片；未圈定文档时上下文为空，并按证据不足处理。跨文档召回不属于本适配器职责。

## 4. Prompt 与结构化输出

- Prompt 资源：`prompts/conversation-answer-system.md`
- Prompt 版本：`conversation-answer-v1`
- Schema 版本：`conversation-answer-schema-v1`
- 客户端标识：`openai-compatible`

模型输出映射为：

```text
ConversationAnswerResult
  answer: String
  citations[]:
    chunkId: String
    quote: String
    startOffset: Integer | null
    endOffset: Integer | null
```

模型只能引用输入集合内的局部 `chunkId`，`quote` 必须逐字来自对应分片。偏移不能准确计算时返回 `null`。没有证据时返回固定的资料不足提示和空引用。LangChain4j 返回类型只负责 JSON 到 Java record 的结构映射；模型 JSON 可解析不代表引用可信，最终引用仍以服务端反查为准。

## 5. 失败分类

| 场景 | 持久化类别 | 处理边界 |
| --- | --- | --- |
| AI 未启用或密钥条件不满足，没有生产 Bean | `answer_client_unavailable` | 不调用外部模型，保留用户消息 |
| JSON 无法映射或回答正文为空 | `answer_invalid_output` | 不记录模型原文，不生成引用，保留用户消息 |
| 模型连接、认证、超时或供应商异常 | `answer_failed` | 只记录会话、空间和异常类型，不记录完整响应 |
| 引用分片不存在、quote/offset 反查失败 | 完成消息的引用失败计数与 `groundingStatus` | 移除无效引用，不把候选引用当作证据 |

## 6. 配置与启停

生产问答客户端复用现有 `ai.enabled`、`ai.provider`、`ai.api-key`、`ai.base-url`、`ai.model`、温度、Token、超时、重试和 JSON Schema 能力配置，不新增第二套模型配置。只有 AI 显式启用且 API Key 条件满足时，`AiConfiguration` 才创建真实聊天模型和生产问答客户端；默认自动测试继续使用 Fake 或禁用路径，不产生外部调用。

## 7. 回滚

关闭 `ai.enabled` 或移除生产 Bean 装配即可退回 `answer_client_unavailable`，会话、用户消息和历史引用继续保留。回滚不需要修改数据库结构，也不删除已有事实。

# 下一任务路线图

更新时间：2026-09-01

> 本文档只规划下一任务：保留一个当前任务和简短候选队列，不保存产品设计、技术设计、已完成历史或验证流水账。产品与架构边界见 [`docs/prd/ai-work-knowledge-graph-maintainer-prd.md`](prd/ai-work-knowledge-graph-maintainer-prd.md)，实现级设计见 `docs/design/`，验证结论见 `docs/tests/`，完成历史通过 Git 追溯。

## 1. 当前任务：有据问答生产客户端最小闭环

### 1.1 目标

为现有 `ConversationAnswerClient` 增加 OpenAI-compatible 生产实现，使知识空间内的只读问答能够调用真实聊天模型生成结构化回答与引用候选，并继续由服务端完成引用逐字反查、`groundingStatus` 判定和事实持久化。

### 1.2 进入条件

会话、消息、引用表及创建/恢复/提交接口已经存在，Fake 客户端回归已经覆盖空间隔离、部分证据、无上下文、来源失效和客户端未启用降级；当前缺少生产 `ConversationAnswerClient`，因此下一切片只补供应商适配，不扩展前端或运行模型。

### 1.3 实施范围

1. 复用现有 OpenAI-compatible 配置和 LangChain4j 适配模式，实现生产 `ConversationAnswerClient`。
2. 固定问答 Prompt、结构化输出 Schema 和版本标识，明确回答、引用候选及无法回答时的输出约束。
3. 保持模型只生成候选回答和引用；空间隔离、分片归属、quote/offset 反查、`groundingStatus` 和数据库写入继续由 `ConversationService` 负责。
4. 保留无配置或功能关闭时的 `answer_client_unavailable` 降级，不在默认测试或启动中调用真实模型。
5. 增加生产适配器的结构化输出、非法输出和模型异常测试，并保持既有 Fake/MySQL 回归通过。

### 1.4 验收标准

- 未配置真实模型或功能关闭时，不产生外部调用，现有失败事实和用户消息保留语义不变。
- 合法模型结果能够映射为回答和引用候选，引用仍需经过服务端逐字校验后才能落库。
- 非法结构、无效引用和模型异常能够区分失败原因，不写入伪造引用，也不丢失已提交的用户消息。
- Java 21 根 Reactor 的 Fake/MySQL 自动回归通过；测试数量和验证边界记录到对应测试报告或交付说明，不写回 PRD/Roadmap。
- 真实 AI 烟测仅在用户单独确认外部调用、环境变量齐备并显式启用 `real-ai` Profile 后执行；未执行时如实标记为未验证。

### 1.5 本任务不做

- 不实现问答前端、引用跳转 UI、SSE 流式输出或断线续传。
- 不新增会话/消息/引用表，不修改既有 HTTP 资源语义。
- 不引入 Milvus、Qdrant、Reranker、Agent 或 Workflow 编排。
- 不允许问答修改来源资料、标签、文档关系或图谱确认事实。

### 1.6 必读入口

- 产品与架构边界：[`docs/prd/ai-work-knowledge-graph-maintainer-prd.md`](prd/ai-work-knowledge-graph-maintainer-prd.md)
- 有据问答设计：[`docs/prd/document-tag-and-association-rag-prd.md` 第 13.7、16.3 节](prd/document-tag-and-association-rag-prd.md#137-有据问答-rag-链路)
- 当前领域接口：`backend/server/src/main/java/com/flevin/knowgraph/server/service/conversation/ConversationAnswerClient.java`
- 当前业务边界：`backend/server/src/main/java/com/flevin/knowgraph/server/service/conversation/impl/ConversationServiceImpl.java`

## 2. 候选队列

当前任务完成后，再按顺序评审并只提升一项为新的当前任务：

1. 问答前端最小闭环：会话恢复、问题提交、回答状态和可点击引用定位。
2. 长耗时问答恢复：评估 SSE、运行标识、断线恢复和幂等续传。
3. 健康检查与导出：失效来源、缺字段、冲突及 Markdown/JSON/图片导出。
4. 受控 Agent：仅在固定 RAG 问答稳定且出现动态工具选择、暂停恢复或可回放多步任务需求时评估。

## 3. 维护规则

- 任务完成后删除当前任务正文，由 Git 和测试报告保存完成事实，再从候选队列提升一个任务。
- 产品或架构变化更新 PRD；实现级方案变化更新 `docs/design/`；验证结果更新 `docs/tests/`。
- Roadmap 不追加完成清单、提交摘要、测试数字、浏览器流水账或按日期累计的阶段记录。
- 每个功能点验证完成后，在本地 Git 提交前自动完成上述文档同步和下一任务提升；本地提交不授权推送。

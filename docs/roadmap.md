# 下一任务路线图

更新时间：2026-09-02

> 本文档只规划下一任务：保留一个当前任务和简短候选队列，不保存产品设计、技术设计、已完成历史或验证流水账。产品与架构边界见 [`docs/prd/ai-work-knowledge-graph-maintainer-prd.md`](prd/ai-work-knowledge-graph-maintainer-prd.md)，实现级设计见 `docs/design/`，验证结论见 `docs/tests/`，完成历史通过 Git 追溯。

## 1. 当前任务：问答前端最小闭环

### 1.1 目标

在现有知识空间工作台接入只读问答面板，使用户能够创建或恢复当前会话、提交问题、区分回答/失败/证据不足状态，并从已验证引用打开对应来源资料。

### 1.2 进入条件

会话、消息、引用事实和创建/恢复/提交/查询接口已经存在；OpenAI-compatible 生产客户端、固定 Prompt、非法输出分类及关闭降级已经形成后端边界。当前缺少 TypeScript 问答契约和用户可操作入口，因此本切片只接前端闭环，不改检索策略或引入长耗时编排。

### 1.3 实施范围

1. 增加会话、消息和引用的 TypeScript 类型及 API 客户端，所有业务 `Long` ID 保持十进制字符串。
2. 在当前知识空间工作台增加只读问答入口，支持创建会话、恢复已知会话、提交问题和展示用户/助手消息。
3. 明确显示 `grounded`、`partially_grounded`、`insufficient_evidence`、`failed` 及稳定错误摘要，不把失败或无证据回答伪装成可信结论。
4. 引用卡片展示文档名、章节路径和逐字 quote；点击后复用现有文档预览打开对应资料，并保留当前空间边界。
5. 增加 API 映射和关键交互测试，并执行前端 typecheck、build 及浏览器关键路径验收。

### 1.4 验收标准

- 创建或恢复会话后可提交问题，页面刷新或工作台状态恢复不会把会话错误带到其他知识空间。
- 消息顺序、提交中、成功、失败、部分证据和证据不足状态均有明确且可访问的展示。
- 已验证引用可以打开正确来源资料并展示章节与 quote；失效来源有显式提示，不能静默指向新版本。
- 前端不保存模型密钥，不把字符串 ID 转为 JavaScript `number`，不使用演示回答兜底真实接口失败。
- `npm run typecheck`、`npm run build` 和浏览器关键路径通过；验证事实只写入 `docs/tests/`。

### 1.5 本任务不做

- 不修改问答检索、Prompt、模型客户端、数据库表或既有 HTTP 资源语义。
- 不实现 SSE、Token 流式输出、断线续传、后台运行队列或会话列表管理。
- 不引入或切换 Embedding、Ollama、Milvus、Qdrant、Reranker、Agent 或 Workflow 编排。
- 不允许通过问答确认标签、采纳关系、删除文档或修改原始资料。

### 1.6 必读入口

- 产品与架构边界：[`docs/prd/ai-work-knowledge-graph-maintainer-prd.md`](prd/ai-work-knowledge-graph-maintainer-prd.md)
- 有据问答设计：[`docs/prd/document-tag-and-association-rag-prd.md` 第 13.7、16.3 节](prd/document-tag-and-association-rag-prd.md#137-有据问答-rag-链路)
- 后端资源：`backend/server/src/main/java/com/flevin/knowgraph/server/controller/conversation/ConversationController.java`
- 前端工作台：`frontend/src/components/graph-workspace.tsx`
- 现有文档 API 模式：`frontend/src/lib/api/documents.ts`

## 2. 候选队列

当前任务完成后，再按顺序评审并只提升一项为新的当前任务：

1. 长耗时问答恢复：评估 SSE、运行标识、断线恢复和幂等续传。
2. 健康检查与导出：失效来源、缺字段、冲突及 Markdown/JSON/图片导出。
3. 受控 Agent：仅在固定 RAG 问答稳定且出现动态工具选择、暂停恢复或可回放多步任务需求时评估。

## 3. 维护规则

- 任务完成后删除当前任务正文，由 Git 和测试报告保存完成事实，再从候选队列提升一个任务。
- 产品或架构变化更新 PRD；实现级方案变化更新 `docs/design/`；验证结果更新 `docs/tests/`。
- Roadmap 不追加完成清单、提交摘要、测试数字、浏览器流水账或按日期累计的阶段记录。
- 每个功能点验证完成后，在本地 Git 提交前自动完成上述文档同步和下一任务提升；本地提交不授权推送。

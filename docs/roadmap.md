# 下一任务路线图

更新时间：2026-09-02

> 本文档只规划下一任务：保留一个当前任务和简短候选队列，不保存产品设计、技术设计、已完成历史或验证流水账。产品与架构边界见 [`docs/prd/ai-work-knowledge-graph-maintainer-prd.md`](prd/ai-work-knowledge-graph-maintainer-prd.md)，实现级设计见 `docs/design/`，验证结论见 `docs/tests/`，完成历史通过 Git 追溯。

## 1. 当前任务：问答前端浏览器点击验收

### 1.1 目标

在浏览器控制工具可用时，对有据问答前端最小闭环补齐真实可见点击验收，确认工作台入口、单文档范围、提交问题、失败态展示、会话 URL 恢复和引用打开行为在浏览器中与已通过的 HTTP/Vitest/构建证据一致。

### 1.2 进入条件

`docs/tests/conversation-frontend-minimal-loop-2026-09-02.md` 已记录前端单元测试、构建、后端回归和本机 HTTP 联调证据；当前只缺真实浏览器可见点击验收。执行前需确认可用的浏览器控制工具、前后端端口、测试空间和虚构资料，不以普通 HTTP 200 替代浏览器交互证据。

### 1.3 实施范围

1. 恢复或重新启动本机后端 `4010` 与前端 `3010`，使用虚构资料和测试空间。
2. 在浏览器中进入工作台“有据问答”视图，选择一份来源资料作为问答范围。
3. 提交一个可复现问题，确认 AI 关闭时展示稳定失败类别和用户可读错误，不伪装为有证据回答。
4. 通过带 `conversationId` 的 URL 恢复会话，确认范围文档、历史消息和空间隔离未丢失。
5. 在存在已验证引用的测试状态下点击引用，确认能打开来源资料并定位可反查原文；失效引用不得可点击。
6. 将浏览器验收步骤、结果、未覆盖边界和必要截图/观察写入新的 `docs/tests/` 报告。

### 1.4 验收标准

- 浏览器可见验证覆盖工作台入口、范围选择、问题提交、回答/失败状态、URL 恢复和引用打开。
- 验收记录明确区分浏览器点击证据、HTTP 联调、Vitest、构建和真实 AI 未验证边界。
- 不读取真实资料，不使用演示数据静默兜底，不把 AI 关闭失败态写成真实回答能力。
- 若浏览器控制工具不可用，记录阻塞原因和已完成的非浏览器证据，不伪造点击验收。

### 1.5 本任务不做

- 不改问答后端业务规则、Prompt、Schema 或数据库结构。
- 不启用真实聊天模型，不评估真实回答质量、模型费用或延迟。
- 不扩展会话列表、SSE、断线续传、跨文档召回、Agent 或移动端适配。
- 不删除或重建生产/非测试数据。

### 1.6 必读入口

- 产品与架构边界：[`docs/prd/ai-work-knowledge-graph-maintainer-prd.md`](prd/ai-work-knowledge-graph-maintainer-prd.md)
- 文档关联与 RAG 设计：[`docs/prd/document-tag-and-association-rag-prd.md`](prd/document-tag-and-association-rag-prd.md)
- 问答生产客户端设计：[`docs/design/conversation-answer-client-v1.md`](design/conversation-answer-client-v1.md)
- 问答前端最小闭环验证：[`docs/tests/conversation-frontend-minimal-loop-2026-09-02.md`](tests/conversation-frontend-minimal-loop-2026-09-02.md)
- 前端入口：`frontend/src/components/conversation-panel.tsx`、`frontend/src/components/graph-workspace.tsx`

## 2. 候选队列

当前任务完成后，再按顺序评审并只提升一项为新的当前任务：

1. 长耗时问答恢复：评估 SSE、运行标识、断线恢复和幂等续传。
2. 健康检查与导出：失效来源、缺字段、冲突及 Markdown/JSON/图片导出。

## 3. 维护规则

- 任务完成后删除当前任务正文，由 Git 和测试报告保存完成事实，再从候选队列提升一个任务。
- 产品或架构变化更新 PRD；实现级方案变化更新 `docs/design/`；验证结果更新 `docs/tests/`。
- Roadmap 不追加完成清单、提交摘要、测试数字、浏览器流水账或按日期累计的阶段记录。
- 每个功能点验证完成后，在本地 Git 提交前自动完成上述文档同步和下一任务提升；本地提交不授权推送。

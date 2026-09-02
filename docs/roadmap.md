# 下一任务路线图

更新时间：2026-09-02

> 本文档只规划下一任务：保留一个当前任务和简短候选队列，不保存产品设计、技术设计、已完成历史或验证流水账。产品与架构边界见 [`docs/prd/ai-work-knowledge-graph-maintainer-prd.md`](prd/ai-work-knowledge-graph-maintainer-prd.md)，实现级设计见 `docs/design/`，验证结论见 `docs/tests/`，完成历史通过 Git 追溯。

## 1. 当前任务：真实 Embedding 语义召回评估

### 1.1 目标

在本地 Ollama `qwen3-embedding:latest` 已完成最小 smoke 后，使用固定虚构资料评估真实 Embedding 对文档语义召回和 RRF 融合排序的影响，判断是否具备进入默认候选链路的证据基础。

### 1.2 进入条件

本地 Ollama OpenAI-compatible `/v1/embeddings` 已可返回 4096 维向量，项目 `DocumentEmbeddingClient` 真实 smoke 已通过。下一步只允许在明确目标测试库和清理边界后运行会写入/清理 MySQL 评估数据的测试。

### 1.3 实施范围

1. 明确测试数据库、上传目录和报告文件名，避免覆盖 Fake 基线报告或误清理非测试数据。
2. 使用以下配置运行真实 Embedding 评估：
   - `AI_EMBEDDING_BASE_URL=http://127.0.0.1:11434/v1`
   - `AI_EMBEDDING_API_KEY=ollama`
   - `AI_EMBEDDING_MODEL=qwen3-embedding:latest`
   - `AI_EMBEDDING_DIMENSION=4096`
   - `AI_EMBEDDING_VERSION=ollama-qwen3-embedding-latest-20260902`
3. 只改变 Embedding 模型来源这一项变量，复用固定资料集、TopK、RRF 常数和评价口径。
4. 将真实评估结果写入新的 `docs/tests/` 报告，不覆盖 Fake 基线。
5. 对比内容臂、语义臂和 RRF 融合臂的 Recall@8、Precision@8、硬负例、自关联和耗时。

### 1.4 验收标准

- 评估运行前已经获得测试库清理授权，并确认不会影响非测试资料。
- 真实向量事实使用 `ollama-qwen3-embedding-latest-20260902` 版本隔离，不与既有模型版本混用。
- 报告明确记录数据集版本、Embedding 模型、维度、TopK、阈值、结果指标、失败样例、结论和未覆盖边界。
- 不能只因真实模型调用成功就判断 RAG 质量提升；必须用固定标注指标和失败样例判断。

### 1.5 本任务不做

- 不调用聊天模型，不验证真实问答生成、标签抽取或关系判断 Prompt。
- 不引入 Milvus、Qdrant、Reranker、Agent、SSE 或 Workflow 编排。
- 不删除或重建生产/非测试数据，不把真实 Embedding 自动接入默认候选路径。
- 不读取真实公司资料；只使用 `fixture/` 下的虚构或脱敏资料。

### 1.6 必读入口

- 产品与架构边界：[`docs/prd/ai-work-knowledge-graph-maintainer-prd.md`](prd/ai-work-knowledge-graph-maintainer-prd.md)
- 文档关联与 RAG 设计：[`docs/prd/document-tag-and-association-rag-prd.md`](prd/document-tag-and-association-rag-prd.md)
- 本地 Ollama 验证记录：[`docs/tests/local-ollama-qwen3-embedding-deployment-20260902.md`](tests/local-ollama-qwen3-embedding-deployment-20260902.md)
- 真实评估入口：`backend/server/src/test/java/com/flevin/knowgraph/server/association/DocumentSemanticRrfEvaluationTests.java`

## 2. 候选队列

当前任务完成后，再按顺序评审并只提升一项为新的当前任务：

1. 问答前端浏览器点击验收：在浏览器控制工具可用时补齐真实可见点击验证。
2. 长耗时问答恢复：评估 SSE、运行标识、断线恢复和幂等续传。
3. 健康检查与导出：失效来源、缺字段、冲突及 Markdown/JSON/图片导出。

## 3. 维护规则

- 任务完成后删除当前任务正文，由 Git 和测试报告保存完成事实，再从候选队列提升一个任务。
- 产品或架构变化更新 PRD；实现级方案变化更新 `docs/design/`；验证结果更新 `docs/tests/`。
- Roadmap 不追加完成清单、提交摘要、测试数字、浏览器流水账或按日期累计的阶段记录。
- 每个功能点验证完成后，在本地 Git 提交前自动完成上述文档同步和下一任务提升；本地提交不授权推送。

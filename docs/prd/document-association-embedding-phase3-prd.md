---
title: 文档关联阶段 3：Embedding 与混合召回实验
version: v0.6
status: 独立语义召回与 RRF 对照（Fake）已通过，待真实 Embedding 质量对照
updated: 2026-08-31
---

# 1. 目标与边界

阶段 3 只验证 Embedding 是否能改善文档关联候选召回，不建设问答、Reranker、Agent 或自动确认关系。

```text
MySQL 章节/分片事实与索引元数据
  → Embedding
  → MySQL 可重建向量事实
  → Java 精确 COSINE 召回
  → 与现有内容候选进行 RRF 对照
```

- **MySQL 是唯一事实源**：保存来源资料、章节、分片、内容指纹、模型/维度、索引状态、关系和审核事实。
- **向量索引先不引入独立数据库**：当前固定资料规模优先使用 MySQL 保存可重建向量事实，由 Java 执行精确 COSINE 扫描；不为实验引入 Milvus、Qdrant 或 PostgreSQL 迁移。
- 主键和关联 ID 使用 Snowflake `BIGINT` / Java `Long`；不使用 UUID 业务 ID、数据库外键或启动迁移。
- 向量记录必须带 `spaceId`、来源资料、分片、内容指纹、模型/版本和维度；模型版本或维度变化必须形成新的兼容索引边界，禁止混用向量。
- 相似度只用于候选排序；正式关系仍必须经过关系判断、逐字证据校验和人工审核。

# 2. 当前状态

- 已完成代码与单元边界：MySQL 8.0 全量 DDL 已由历史 17 张扩展为 20 张业务表；章节/分片事实、`DocumentEmbeddingClient`、确定性 Fake、可重建向量事实、只补缺失向量的索引服务和 Java 精确 COSINE 召回已实现，但不接入默认关联候选。
- 已完成 MySQL 集成验证：本机 MySQL 8.0 无备份重建并执行 20 表全量 DDL，Java 21 根 Reactor 99 项 MySQL/Fake 回归全部通过；回归后再次重建空库，精确验证 20 张业务表总行数为 0、三张阶段 3 表存在、表/字段注释无缺失且没有数据库外键。
- 已完成独立语义召回与 RRF 对照（Fake，2026-08-31）：新增 `document-semantic-recall-v1` 独立语义召回（主体分片向量为查询、文档级 max 池化、分片层自排除）和 `RRF constant=60` 融合工具；固定 12 份资料对照报告见 `docs/tests/document-association-semantic-rrf-evaluation-v1.md`。内容臂复现 v1 基线 Recall@8=1.0000、Precision@8=0.1707；Fake 语义臂如预期引入硬负例噪声（融合臂 Precision@8 降至 0.1321、硬负例 1 个），三臂自关联与跨空间候选均为 0；47 个分片向量均可从 MySQL 重建，向量建立 12 次批量请求约 514ms，7 次语义查询约 89ms 且查询阶段 0 次新 Embedding 请求。该对照只证明数据流和边界，不构成接入依据。
- 未完成质量对照：真实 OpenAI-compatible `DocumentEmbeddingClient` 与真实 Embedding 对照尚未实现或运行。
- 当前无向量候选基线：`document-candidate-recall-v1/v3`；Precision@8 为 `0.1707`。

# 3. 实施计划

## 3.1 MySQL + Java 精确语义检索（代码、单元与 MySQL 集成已完成）

1. 在完整 MySQL DDL 中保存章节、分片和向量索引状态事实；原文、内容指纹、模型/版本和维度必须支持重建。
2. 通过供应商无关的 `DocumentEmbeddingClient` 生成 Fake 向量；真实 Embedding 只在显式配置和受控预算下调用。
3. 在 Java 中按知识空间、文档版本、模型/版本和维度过滤，执行精确 COSINE TopK；不把语义结果接入默认关联候选。

验收：Fake/单元测试证明空间隔离、主体排除、维度与有限值校验、空索引和稳定排序。该结果不代表大规模 ANN 性能、真实模型质量或生产部署。

## 3.2 MySQL 章节与分片事实（已完成 MySQL 集成验证）

在完整 `schema.sql` 中新增 `document_sections`、`document_chunks`、`document_chunk_index_states`，所有表和字段写中文 `COMMENT`。不增加启动 DDL 或数据库外键。

验收：20 表全量 DDL、Fake/MySQL 测试已覆盖空间隔离、内容版本、偏移反查、幂等和事务失败；本机 Java 21 根 Reactor 99 项测试通过。回归后数据库已恢复为 20 表、0 业务数据的干净起点。

## 3.3 Embedding 抽象与可重建向量事实（Fake/MySQL 数据流已完成）

新增 `DocumentEmbeddingClient`、确定性 Fake 和 MySQL 向量事实 Repository。仅为缺失且兼容的分片请求向量；服务端校验返回数量、维度和有限值后批量写入事实表并记录索引状态。后续若分片规模证明精确扫描不足，再单独评估 JVector 或 Qdrant，不在本阶段预埋独立服务。

验收：默认测试不需要 API Key；MySQL/Java 测试覆盖重建、空间过滤、主体排除、版本/维度不兼容、空索引和稳定排序。

## 3.4 独立语义召回与 RRF 对照（Fake 数据流已完成，2026-08-31）

以 `TopK=8`、`RRF constant=60` 对比现有内容候选和“内容 + 语义”候选。第一轮只产出实验报告，不修改 `DocumentAssociationService` 的默认候选输入。

实现与验收：

1. 新增 `document-semantic-recall-v1` 独立语义召回：确定性解析并幂等持久化主体章节/分片 → 只补缺失向量 → 以主体分片向量为查询执行精确 COSINE（分片层排除主体资料）→ 按文档 max 池化聚合为文档级 TopK=8 候选。
2. 新增 RRF 融合工具（constant=60），只使用两路排名融合内容臂与语义臂。
3. 固定资料评估测试对 7 个冻结召回用例计算内容臂、语义臂、融合臂的 Recall@8、Precision@8、硬负例、自关联和跨空间候选，并生成实验报告 `docs/tests/document-association-semantic-rrf-evaluation-v1.md`。

验收结果：内容臂复现 v1 基线（1.0000/0.1707）；Fake 语义臂 Recall@8=1.0000、Precision@8=0.1346、硬负例 3 个；融合臂 Recall@8=1.0000、Precision@8=0.1321、硬负例 1 个；三臂自关联与跨空间均为 0；语义召回两次运行结果一致。该结果只证明 RRF 对照数据流可用；Fake 字符哈希向量无语义，融合臂 Precision 下降符合预期，不能据此判断真实 Embedding 的收益或损失。

## 3.5 真实 Embedding 对照（已完成，2026-08-31；未达接入门槛）

默认回归继续使用 Fake。真实 Embedding 客户端 `OpenAiCompatibleDocumentEmbeddingClient` 已实现：包装 LangChain4j `EmbeddingModel`，供应商、模型、显式维度（`AI_EMBEDDING_DIMENSION`）和实验版本（`AI_EMBEDDING_VERSION`）全部配置化；返回数量、维度和有限值在写入前整批校验。仅在 `AI_ENABLED=true` + `AI_EMBEDDING_ENABLED=true` 时以 Spring 主候选覆盖 Fake，默认自动回归不受影响（根 Reactor 112 项 Fake 回归通过）。Embedding 支持独立端点：`AI_EMBEDDING_BASE_URL` / `AI_EMBEDDING_API_KEY`，为空时回退聊天端点配置。

本次真实对照配置（用户提供，已确认写入配置文件）：阿里云百炼 DashScope OpenAI-compatible 端点 + `qwen3.7-text-embedding`（1024 维，免费额度），`application.yml` 保存 embedding 独立端点默认值（`AI_EMBEDDING_BASE_URL` / `AI_EMBEDDING_API_KEY` / `AI_EMBEDDING_MODEL` / `AI_EMBEDDING_DIMENSION` / `AI_EMBEDDING_VERSION`），环境变量仍可覆盖。真实模式开关使用环境变量 `TEST_REAL_EMBEDDING=true` 与 `TEST_SEMANTIC_RRF_REPORT_NAME`（注意：surefire 不透传命令行 `-D` 到 forked JVM 时占位符会回退默认值，必须用环境变量）。

真实对照结果（报告 `docs/tests/document-association-semantic-rrf-evaluation-real-qwen3.7-embedding-v1.md`）：

- 内容臂复现 v1 基线：Recall@8=1.0000、Precision@8=0.1707。
- 真实语义臂：Recall@8=1.0000、Precision@8=0.1400、硬负例 4 个；孤立文档（打印机维保通知）被召回 4 个年会文档，违背"孤立文档空召回"预期。
- RRF 融合臂：Recall@8=1.0000、Precision@8=0.1373、硬负例 4 个；自关联与跨空间均为 0。
- 47 分片/47 向量可从 MySQL 重建；向量建立 12 次批量请求 20.5s；查询 176ms 且 0 次新 Embedding 请求。
- real-ai 烟测（psydo 聊天 + DashScope Embedding）通过；顺带修复 `real-ai` Maven profile 激活时 `profile.active` 缺失导致 `@profile.active@` 不过滤的缺陷。

**结论：真实 Embedding 对照未达到阶段 3 接入门槛**（Precision@8 低于 0.1707、新增硬负例、孤立文档未空召回）。按 PRD 第 4 节保留实验报告，默认内容召回继续运行，不修改 `DocumentAssociationService` 候选输入。可能原因与下一步（均为实验参数迭代，不改默认链路）：文档级语义 TopK=8 在 12 份语料上近似"全召回"，且语义召回未使用分数阈值（`ai.rag.min-score` 未参与）；后续实验应评估语义文档级候选的分数下限、缩小语义 TopK，或让语义臂只作为"补内容漏召回"通道而非与内容臂并列 RRF。每轮仍只改一个变量并重跑对照。

## 3.6 语义分数阈值扫描实验（已完成，2026-08-31；阈值单独不能达标）

在 3.5 真实向量化结果上做阈值扫描，本轮唯一变量为"语义文档级候选分数下限（bestChunkScore 阈值）"，10 个阈值复用同一次 Embedding 结果，无额外模型调用，不改任何默认链路。报告：`docs/tests/document-association-semantic-threshold-sweep-real-qwen3.7-embedding-v1.md`。

结果趋势（融合臂）：

- 阈值单调改善：Precision@8 从 0.1373（无阈值）升至 0.1591（0.70），硬负例从 4 降至 1，孤立文档语义候选从 4 清零（阈值 ≥ 0.50）。
- 但扫描范围（0.00–0.70）内没有任何阈值同时满足全部门槛：融合 Precision@8 最高 0.1591 仍低于 0.1707，且硬负例残留 1 个。
- 有价值的观察：阈值 0.70 时语义臂自身 Precision@8 为 0.2188，已高于内容臂基线 0.1707，说明高置信语义候选本身质量不错；融合臂收益被 RRF 并集语义稀释，噪声候选仍留在 TopK=8 内。

**结论：分数阈值方向正确但单独不能达标。** 下一轮单变量候选：缩小语义臂 TopK（如 8→3）、语义臂只做"补内容漏召回"通道（并集改补集）、或扫描更高阈值区间 0.75–0.90。

## 3.7 语义补集融合对照实验（已完成，2026-08-31；结构性确认不可达标）

本轮唯一变量为融合方式：内容臂优先 + 语义补集（内容臂候选全部保留原序，语义只补充内容臂没有且分数不低于阈值的文档），与 3.6 的 RRF 并集逐阈值对照，两种融合复用同一次真实向量化结果。报告：`docs/tests/document-association-semantic-supplement-sweep-real-qwen3.7-embedding-v1.md`。

结果：

- 补集融合在各阈值下的 Precision@8 均不高于内容臂基线 0.1707（最佳 0.70 阈值下为 0.1591、硬负例 1）；孤立文档候选与并集相同（≥0.50 清零）。
- 相比并集，补集只在低阈值区间把融合硬负例从 4 降到 3，其余指标完全一致。

**结构性结论：在 document-association-eval-v1 上，任何"向 TopK=8 添加文档"的融合方式都不可能超过内容臂 Precision@8=0.1707**——内容臂 Recall@8 已达 1.0000，没有可补的内容漏召回正例，融合只会扩大候选分母。这完成了对 PRD 第 4 节门槛在该数据集上的系统性证伪：并集 RRF（3.5）、阈值扫描（3.6）、补集通道（3.7）三种方案均不可达标，原因不在融合参数，而在数据集本身（12 份小语料、内容臂已满召回）。

**阶段 3 实验主线就此收口。** 后续如需继续验证语义价值，前置条件是扩充冻结评估集（新增内容漏召回正例场景、更大语料）；在此之前语义召回作为实验能力保留，不进入默认链路。

## 3.8 v2 冻结评估集与语义补充价值验证（已完成，2026-08-31；语义价值首次被证实）

按 3.7 的结论扩充冻结评估集 `document-association-eval-v2`（11 份虚构文档，"星桥科技·青禾计划"新项目线）：

- **设计契约**：新增 `contentRecallExpectation` 字段，逐用例声明内容通道预期（missed/recalled/partial/empty）。5 个漏召回正例覆盖三类词面零重叠场景：同义改写（知识库建设 vs 话术归档）、中英缩写（CRM vs 客户关系管理）、口语会议速记 vs 正式执行清单；2 个内容可召回对照用例（显式互引 + 共享词面）；2 组语义共现硬负例（"机器人"歧义、青禾跨子系统）；1 个孤立文档 + 1 份超分片长文档。
- **静态验收**：`scripts/validate-document-association-fixture-v2.mjs`（结构、逐字证据、漏召回用例数下限、显式互引防线抽查）。
- **内容臂基线**（进入默认回归，报告 `docs/tests/document-association-v2-content-recall-baseline.md`）：Recall@8 = 0.3333，6 个期望候选被内容通道漏掉，冻结契约全部成立。
- **真实 Embedding 对照**（`qwen3.7-text-embedding`，报告 `docs/tests/document-association-v2-semantic-evaluation-real-qwen3.7-embedding-v1.md`）：
  - **语义臂补回全部 5 个漏召回正例**，融合臂 Recall@8 从 0.3333 升至 1.0000，融合 Precision@8 = 0.1837（高于 v1 基线 0.1707）。
  - 对照 PRD 第 4 节门槛：Recall 不下降且恢复漏召回正例 ✓、Precision ✓、自关联/跨空间 ✓、命中可反查 MySQL 分片 ✓；**未达标项**：硬负例 2 个（机器人歧义对、孤立文档产生 5 个语义候选）。

**结论：语义召回的补充价值在 v2 上首次被真实模型证实**——3.7 的结构性判断（价值要在内容漏召回场景体现）成立。距接入门槛只差硬负例一项；结合 3.6 已证明的"阈值单调降噪、孤立文档候选 ≥0.50 清零"，下一步单变量实验为"v2 + 语义分数阈值"，验证降噪后是否同时满足全部门槛。

## 3.9 v2 语义分数阈值扫描（已完成，2026-08-31；阈值 0.60/0.65 首次全门槛通过）

在 v2 的真实向量化结果上扫描 13 个阈值（0.00–0.85，复用同一次 Embedding，无额外调用）。报告：`docs/tests/document-association-v2-threshold-sweep-real-qwen3.7-embedding-v1.md`。

关键结果：

- **阈值 0.60 与 0.65 首次同时满足 PRD 第 4 节全部门槛**：
  - 0.60：融合 Recall@8 = 0.7778（内容臂 0.3333 的 2.3 倍）、Precision@8 = 0.3182、硬负例 0、孤立文档语义候选 0。
  - 0.65：Recall@8 = 0.5556、Precision@8 = 0.3571、硬负例 0、孤立候选 0。
  - 0.70 及以上：阈值过高，漏召回正例被过滤，Recall 回落到内容臂水平。
- 无阈值时仍为 2 个硬负例、5 个孤立候选，与 3.8 一致；阈值单调降噪的规律与 3.6 相互印证。

**结论：语义候选接入的指标条件已具备**。若接入，推荐文档级阈值为 0.60（Recall 增益最大且全门槛通过），实现 `includeSemanticCandidates` 用户开关（默认关闭）与 `ai.rag.semantic-min-score` 配置项，并完成回滚验证；是否接入与默认值取值由用户确认。

## 3.10 接入实现（已完成，2026-09-01；开关默认关闭，经用户确认）

用户确认按推荐实现接入开关（阈值默认 0.60）。实现内容：

- **配置**：`ai.rag.semantic-min-score`（环境变量 `AI_RAG_SEMANTIC_MIN_SCORE`，默认 0.60），来自 3.9 扫描的全门槛区间。
- **语义服务**：`DocumentSemanticRecallService` 新增带分数下限的召回方法（默认方法，按 `bestChunkScore` 过滤并重赋秩）；实验评估继续使用无阈值方法保持可比。
- **候选召回**：`DocumentCandidateRecallService` 新增语义增强通道，融合策略版本 `document-candidate-recall-semantic-v1`：内容候选与阈值过滤后的语义候选按 RRF 并集融合；语义补充候选标记 `semantic_match` 通道并补齐摘要上下文；**语义召回失败时降级为纯内容结果并保持内容策略版本**，恢复端可区分；语义通道会幂等补建章节、分片与向量事实，因此该方法必须使用可写事务（只读事务内的向量写入会触发 rollback-only）。
- **关联运行**：`createRun` 新增 `includeSemanticCandidates` 参数与 HTTP 查询参数（默认 false），运行记录填充 `semanticCandidateCount` 与策略版本。
- **前端**：文档标签面板新增"按语义相似补充候选"入口，运行结果展示语义通道命中数。
- **回滚验证**：开关关闭时行为与历史基线逐值一致（集成测试断言关闭路径复现 v1 策略版本与候选数量）；回滚即关闭开关。

验证：114 项 Fake 回归通过；前端 typecheck 与生产构建通过。真实验证边界：端到端语义关联运行还需要启用文档关联判断模型，当前仅验证了候选召回层数据流。

## 3.11 真实关联判断客户端与端到端验证（已完成，2026-09-01）

实现 `document-association-v1` Prompt 与 `OpenAiCompatibleDocumentAssociationClient`：AI Service 结构化输出关联判断，渲染当前文档与候选的有限分片上下文（当前文档最多 8 片、候选各 3 片），校验判断数量与候选一一对应；`AiConfiguration` 注册 Bean 后，真实环境下关联运行可端到端执行。

修复语义增强召回与运行快照的版本一致性问题：运行创建时按开关冻结的 `document-candidate-recall-semantic-v1` 版本不可回退，融合不变或语义失败降级时仅切换版本，是否真正融合由 `semanticCandidateCount` 区分。

real-ai 端到端验证（`DocumentAssociationRealAiEndToEndTests`）：v2 资料上从话术归档需求发起 `includeSemanticCandidates=true` 的完整关联运行——内容召回 → 真实 DashScope 语义召回 → RRF 融合 → 真实聊天判断（psydo 端点）→ 服务端证据校验 → 建议持久化，运行 completed、建议关系均带逐字证据、自关联为 0。该测试只证明链路连通和服务端校验有效，不证明真实模型判断质量。

# 4. 接入门槛与回滚

只有同时满足以下条件，才另行评估 `includeSemanticCandidates`：

- 既有正例 Recall@8 不下降，且至少恢复 1 个已标注的内容漏召回正例。
- 自关联、跨空间候选均为 0，且不新增明确硬负例。
- Precision@8 不低于 `0.1707`。
- 命中可反查 MySQL 分片；向量事实可从 MySQL 章节/分片完整重建并已验证回滚。
- 上述质量结论来自显式真实 Embedding 对照，不能只依赖 Fake。

未达标时保留实验报告，默认内容召回和人工审核继续运行，不接入语义候选，也不修改历史关系或审核状态。

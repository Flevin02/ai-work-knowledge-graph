---
title: 文档关联阶段 3：Embedding 与混合召回实验
version: v0.4
status: MySQL/Fake 精确检索骨架已实现，待集成与质量对照
updated: 2026-08-28
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
- 未完成验证与质量对照：新增三表尚未在本机 MySQL 执行，真实 OpenAI-compatible `DocumentEmbeddingClient`、RRF 固定资料评估和真实 Embedding 对照尚未实现或运行。
- 当前无向量候选基线：`document-candidate-recall-v1/v3`；Precision@8 为 `0.1707`。

# 3. 实施计划

## 3.1 MySQL + Java 精确语义检索（代码与单元边界已完成）

1. 在完整 MySQL DDL 中保存章节、分片和向量索引状态事实；原文、内容指纹、模型/版本和维度必须支持重建。
2. 通过供应商无关的 `DocumentEmbeddingClient` 生成 Fake 向量；真实 Embedding 只在显式配置和受控预算下调用。
3. 在 Java 中按知识空间、文档版本、模型/版本和维度过滤，执行精确 COSINE TopK；不把语义结果接入默认关联候选。

验收：Fake/单元测试证明空间隔离、主体排除、维度与有限值校验、空索引和稳定排序。该结果不代表大规模 ANN 性能、真实模型质量或生产部署。

## 3.2 MySQL 章节与分片事实（代码已完成，MySQL 集成待确认）

在完整 `schema.sql` 中新增 `document_sections`、`document_chunks`、`document_chunk_index_states`，所有表和字段写中文 `COMMENT`。不增加启动 DDL 或数据库外键。

验收：Fake/MySQL 测试覆盖空间隔离、内容版本、偏移反查、幂等和事务失败。

## 3.3 Embedding 抽象与可重建向量事实（Fake 数据流已完成）

新增 `DocumentEmbeddingClient`、确定性 Fake 和 MySQL 向量事实 Repository。仅为缺失且兼容的分片请求向量；服务端校验返回数量、维度和有限值后批量写入事实表并记录索引状态。后续若分片规模证明精确扫描不足，再单独评估 JVector 或 Qdrant，不在本阶段预埋独立服务。

验收：默认测试不需要 API Key；MySQL/Java 测试覆盖重建、空间过滤、主体排除、版本/维度不兼容、空索引和稳定排序。

## 3.4 独立语义召回与 RRF 对照（下一开发切片）

以 `TopK=8`、`RRF constant=60` 对比现有内容候选和“内容 + 语义”候选。第一轮只产出实验报告，不修改 `DocumentAssociationService` 的默认候选输入。

记录：Recall@8、Precision@8、硬负例、自关联、跨空间候选、向量生成/查询耗时、请求数、失败样例、模型/维度、索引状态和参数版本。

## 3.5 真实 Embedding 对照

默认回归继续使用 Fake。仅在用户确认可产生少量外部费用、并由环境变量提供 AI 配置后运行：

```bash
cd backend
kg_java_home=$(/usr/libexec/java_home -v 21)
JAVA_HOME="$kg_java_home" PATH="$kg_java_home/bin:$PATH" \
mvn -pl server -am -Preal-ai -Dtest=RealAiSmokeIntegrationTests test
```

该烟测只证明当次端点认证、聊天非空响应、Embedding 维度和有限值；不代表关系质量、精确扫描性能或生产可用性。

# 4. 接入门槛与回滚

只有同时满足以下条件，才另行评估 `includeSemanticCandidates`：

- 既有正例 Recall@8 不下降，且至少恢复 1 个已标注的内容漏召回正例。
- 自关联、跨空间候选均为 0，且不新增明确硬负例。
- Precision@8 不低于 `0.1707`。
- 命中可反查 MySQL 分片；向量事实可从 MySQL 章节/分片完整重建并已验证回滚。
- 上述质量结论来自显式真实 Embedding 对照，不能只依赖 Fake。

未达标时保留实验报告，默认内容召回和人工审核继续运行，不接入语义候选，也不修改历史关系或审核状态。

---
title: 文档关联阶段 3：Milvus Embedding 与混合召回
version: v0.2
status: 架构已确认，待实施
updated: 2026-08-27
---

# 1. 目标与边界

阶段 3 只验证 Embedding 是否能改善文档关联候选召回，不建设问答、Reranker、Agent 或自动确认关系。

```text
MySQL 章节/分片事实与索引元数据
  → Embedding
  → Milvus 派生向量索引
  → COSINE 语义召回
  → 与现有内容候选进行 RRF 对照
```

- **MySQL 是唯一事实源**：保存来源资料、章节、分片、内容指纹、模型/维度、索引状态、关系和审核事实。
- **Milvus 是可重建索引**：仅保存派生向量和检索过滤字段，不保存原始全文、唯一业务事实或审核状态。
- 主键和关联 ID 使用 Snowflake `BIGINT` / Java `Long`；不使用 UUID 业务 ID、数据库外键或启动迁移。
- Milvus 使用与 `document_chunks.id` 对齐的 `INT64` 主键、`spaceId` 等元数据过滤和 `COSINE` 检索。模型版本或维度变化必须使用新的兼容 collection，禁止混用向量。
- 相似度只用于候选排序；正式关系仍必须经过关系判断、逐字证据校验和人工审核。

# 2. 当前状态

- 已完成：MySQL 8.0 全量 DDL、17 张业务表、Long ID、HTTP/SSE 字符串 ID、Fake/MySQL 自动回归，以及默认隔离的 `real-ai` 烟测入口。
- 未完成：Milvus 安装与运行、章节/分片事实表、`DocumentEmbeddingClient`、Milvus Java 接入、语义召回、RRF 评估和真实 Embedding 对照。
- 当前无向量候选基线：`document-candidate-recall-v1/v3`；Precision@8 为 `0.1707`。

# 3. 实施计划

## 3.1 Milvus 独立环境最小就绪（下一任务）

1. 核对 Homebrew 实际可用的 Milvus 安装和启动方式；不假定 formula 或服务名。
2. 只使用本项目独立端口和数据目录；不设置全局开机启动，不导入其他项目数据。
3. 验证启动、健康检查、停止和数据目录清理方式。

验收：本机独立 Milvus 可启动、可检查、可停止。此结果不代表 collection、Embedding、召回质量或生产部署已验证。

## 3.2 MySQL 章节与分片事实

在完整 `schema.sql` 中新增 `document_sections`、`document_chunks`、`document_chunk_index_states`，所有表和字段写中文 `COMMENT`。不增加启动 DDL 或数据库外键。

验收：Fake/MySQL 测试覆盖空间隔离、内容版本、偏移反查、幂等和事务失败。

## 3.3 Embedding 抽象与 Milvus 索引

新增 `DocumentEmbeddingClient`、确定性 Fake、OpenAI-compatible 适配器和 Milvus Repository。仅为缺失且兼容的分片请求向量；服务端校验返回数量、维度和有限值后批量写入 Milvus，并在 MySQL 记录索引状态。

验收：默认测试不需要 API Key；本地 Milvus 测试覆盖重建、空间过滤、主体排除、版本/维度不兼容、空索引和稳定排序。

## 3.4 独立语义召回与 RRF 对照

以 `TopK=8`、`RRF constant=60` 对比现有内容候选和“内容 + 语义”候选。第一轮只产出实验报告，不修改 `DocumentAssociationService` 的默认候选输入。

记录：Recall@8、Precision@8、硬负例、自关联、跨空间候选、索引/查询耗时、请求数、失败样例、模型/维度/collection 和参数版本。

## 3.5 真实 Embedding 对照

默认回归继续使用 Fake。仅在用户确认可产生少量外部费用、并由环境变量提供 AI 配置后运行：

```bash
cd backend
kg_java_home=$(/usr/libexec/java_home -v 21)
JAVA_HOME="$kg_java_home" PATH="$kg_java_home/bin:$PATH" \
mvn -pl server -am -Preal-ai -Dtest=RealAiSmokeIntegrationTests test
```

该烟测只证明当次端点认证、聊天非空响应、Embedding 维度和有限值；不代表关系质量、Milvus 质量或生产可用性。

# 4. 接入门槛与回滚

只有同时满足以下条件，才另行评估 `includeSemanticCandidates`：

- 既有正例 Recall@8 不下降，且至少恢复 1 个已标注的内容漏召回正例。
- 自关联、跨空间候选均为 0，且不新增明确硬负例。
- Precision@8 不低于 `0.1707`。
- 命中可反查 MySQL 分片；Milvus 可从 MySQL 完整重建并已验证回滚。
- 上述质量结论来自显式真实 Embedding 对照，不能只依赖 Fake。

未达标时保留实验报告，默认内容召回和人工审核继续运行，不接入语义候选，也不修改历史关系或审核状态。

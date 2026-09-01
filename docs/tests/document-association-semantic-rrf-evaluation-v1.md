# 文档关联独立语义召回与 RRF 对照实验报告 v1

- datasetVersion：document-association-eval-v1
- 内容候选召回：document-candidate-recall-v1，TopK=8
- 语义候选召回：document-semantic-recall-v1，文档级 TopK=8，分片查询 TopK=8
- 融合方式：RRF，constant=60，TopK=8
- 分片策略版本：prd-markdown-section-v1+section-aware-v1:max-1500:overlap-150
- Embedding：Fake（EmbeddingModelDescriptor[provider=fake, model=deterministic-char-hash, version=fake-embedding-v1, dimension=8]）
- 运行方式：Java 21 + MySQL + 确定性 Fake Embedding + 精确 COSINE 扫描
- 计分范围：7 个冻结召回用例、7 个期望候选；未标注文档对不计分

## 指标

| 指标 | 内容臂 | 语义臂 | 内容+语义 RRF 融合臂 |
| --- | ---: | ---: | ---: |
| Recall@8（微平均） | 1.0000 | 1.0000 | 1.0000 |
| Precision@8（微平均） | 0.1707 | 0.1346 | 0.1321 |
| 硬负例命中数 | 0 | 3 | 1 |
| 自关联数 | 0 | 0 | 0 |
| 跨空间候选数 | 0 | 0 | 0 |

## 向量索引与耗时

- 分片事实总数：47
- 向量事实总数：47（均可从 MySQL 章节/分片事实重建）
- 批量 Embedding 请求次数：12（每份资料 1 次批量请求，仅补缺失向量）
- 向量建立阶段总耗时：520 ms
- 7 次语义查询总耗时：124 ms（查询复用已存储向量，0 次新 Embedding 请求）

## 召回用例

| caseId | 期望候选 | 内容臂 | 语义臂 | 融合臂 | 硬负例命中（内容/语义/融合） |
| --- | --- | --- | --- | --- | --- |
| retrieve-plan-from-explicit-reference | doc-annual-plan-v1 | doc-annual-budget-draft, doc-annual-finance-review, doc-annual-plan-v1, doc-annual-plan-v2, doc-execution-handbook, doc-publicity-plan, doc-second-meeting, doc-venue-comparison | doc-annual-finance-review, doc-annual-plan-v1, doc-annual-plan-v2, doc-execution-handbook, doc-second-meeting, doc-training-budget, doc-venue-comparison | doc-annual-budget-draft, doc-annual-finance-review, doc-annual-plan-v1, doc-annual-plan-v2, doc-execution-handbook, doc-publicity-plan, doc-second-meeting, doc-venue-comparison |  / doc-training-budget /  |
| retrieve-meeting-history-and-support | doc-kickoff-meeting, doc-venue-comparison | doc-annual-plan-v1, doc-annual-plan-v2, doc-execution-handbook, doc-kickoff-meeting, doc-venue-comparison | doc-annual-budget-draft, doc-annual-finance-review, doc-annual-plan-v2, doc-execution-handbook, doc-kickoff-meeting, doc-printer-maintenance, doc-publicity-plan, doc-venue-comparison | doc-annual-budget-draft, doc-annual-finance-review, doc-annual-plan-v1, doc-annual-plan-v2, doc-execution-handbook, doc-kickoff-meeting, doc-publicity-plan, doc-venue-comparison |  /  /  |
| retrieve-budget-conflict | doc-annual-finance-review | doc-annual-finance-review, doc-annual-plan-v1, doc-annual-plan-v2, doc-execution-handbook, doc-kickoff-meeting, doc-publicity-plan | doc-annual-finance-review, doc-annual-plan-v1, doc-execution-handbook, doc-kickoff-meeting, doc-publicity-plan, doc-retrospective-template, doc-second-meeting, doc-training-budget | doc-annual-finance-review, doc-annual-plan-v1, doc-annual-plan-v2, doc-execution-handbook, doc-kickoff-meeting, doc-publicity-plan, doc-second-meeting, doc-training-budget |  / doc-training-budget / doc-training-budget |
| retrieve-content-related-publicity | doc-annual-plan-v1 | doc-annual-budget-draft, doc-annual-finance-review, doc-annual-plan-v1, doc-annual-plan-v2, doc-execution-handbook, doc-kickoff-meeting | doc-annual-budget-draft, doc-annual-finance-review, doc-annual-plan-v1, doc-execution-handbook, doc-kickoff-meeting, doc-second-meeting, doc-training-budget, doc-venue-comparison | doc-annual-budget-draft, doc-annual-finance-review, doc-annual-plan-v1, doc-annual-plan-v2, doc-execution-handbook, doc-kickoff-meeting, doc-second-meeting, doc-training-budget |  /  /  |
| retrieve-version-predecessor | doc-annual-plan-v1 | doc-annual-budget-draft, doc-annual-finance-review, doc-annual-plan-v1, doc-execution-handbook, doc-kickoff-meeting, doc-publicity-plan, doc-second-meeting, doc-venue-comparison | doc-annual-budget-draft, doc-annual-finance-review, doc-annual-plan-v1, doc-execution-handbook, doc-publicity-plan, doc-retrospective-template, doc-second-meeting, doc-venue-comparison | doc-annual-budget-draft, doc-annual-finance-review, doc-annual-plan-v1, doc-execution-handbook, doc-kickoff-meeting, doc-publicity-plan, doc-second-meeting, doc-venue-comparison |  /  /  |
| retrieve-long-document-neighbor | doc-annual-plan-v2 | doc-annual-budget-draft, doc-annual-finance-review, doc-annual-plan-v1, doc-annual-plan-v2, doc-kickoff-meeting, doc-publicity-plan, doc-second-meeting, doc-venue-comparison | doc-annual-finance-review, doc-annual-plan-v1, doc-annual-plan-v2, doc-kickoff-meeting, doc-publicity-plan, doc-retrospective-template, doc-second-meeting, doc-venue-comparison | doc-annual-budget-draft, doc-annual-finance-review, doc-annual-plan-v1, doc-annual-plan-v2, doc-kickoff-meeting, doc-publicity-plan, doc-second-meeting, doc-venue-comparison |  / doc-retrospective-template /  |
| retrieve-empty-isolated-document |  |  | doc-execution-handbook, doc-kickoff-meeting, doc-retrospective-template, doc-second-meeting, doc-venue-comparison | doc-execution-handbook, doc-kickoff-meeting, doc-retrospective-template, doc-second-meeting, doc-venue-comparison |  /  /  |

## 结论与下一步

内容臂复现 v1 基线：Recall@8 = 1.0000，Precision@8 = 0.1707（对照基线 1.0000 / 0.1707）。Fake Embedding下语义臂 Recall@8 = 1.0000，融合臂 Recall@8 = 1.0000、Precision@8 = 0.1321；融合臂自关联 0、跨空间候选 0，边界约束全部满足。

在Fake Embedding下融合臂出现指标变化（召回变差：false，精确率变差：true，硬负例增加：true）；这符合字符哈希向量无语义的预期，说明 RRF 对语义臂排名敏感，真实 Embedding 对照（阶段 3.5）才能给出质量结论。

## 解释与边界

本报告使用确定性 Fake Embedding（字符哈希，固定维度），分片级相似度只反映字符分布，不代表真实语义相关性；因此三条候选臂的质量差异不能作为语义候选接入默认文档关联链路的依据。按阶段 3 接入门槛，只有满足 Recall@8 不下降、恢复至少 1 个内容漏召回正例、硬负例与自关联为 0、Precision@8 不低于 0.1707 且命中可反查 MySQL 分片时，才另行评估 includeSemanticCandidates。本结果也不代表大规模 ANN 性能、真实模型并发或生产部署质量。

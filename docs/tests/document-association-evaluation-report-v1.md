# 文档关联固定资料评估报告 v1

- datasetVersion：document-association-eval-v1
- Prompt：document-association-v1（Fake 基线）
- Schema：document-association-v1
- 候选召回：document-candidate-recall-v1，TopK=8
- 关联策略：document-association-policy-v1
- 运行方式：Java 21 + MySQL + 固定 Fake Association Client
- 计分范围：7 条正例、5 组明确负例、7 个召回用例；未标注文档对忽略

## 指标

| 指标 | 结果 | 门槛 |
| --- | ---: | ---: |
| Recall@8 | 1.0000 | >= 0.90 |
| Precision@8（微平均） | 0.1707 | 记录 |
| 关系类型准确率 | 1.0000 | 记录 |
| 有向关系方向准确率 | 1.0000 | 记录 |
| 证据有效率 | 1.0000 | 1.00 |
| 非 none Precision | 1.0000 | >= 0.80 |
| 无依据建议率 | 0.0000 | 0.00 |
| 重复建议率 | 0.0000 | 0.00 |
| 硬负例召回数量 | 0 | 记录 |
| 自关联数量 | 0 | 0 |
| 跨空间关系数量 | 0 | 0 |
| 最终非 none 建议数 | 7 | 记录 |

## 失败样例

本次 Fake 基线未发现正例漏检、最终关系误报、证据上下文缺失或运行失败。

## 召回用例

| caseId | 期望候选 | 实际候选 | 硬负例命中 |
| --- | --- | --- | --- |
| retrieve-plan-from-explicit-reference | doc-annual-plan-v1 | doc-annual-budget-draft, doc-annual-finance-review, doc-annual-plan-v1, doc-annual-plan-v2, doc-execution-handbook, doc-publicity-plan, doc-second-meeting, doc-venue-comparison |  |
| retrieve-meeting-history-and-support | doc-kickoff-meeting, doc-venue-comparison | doc-annual-plan-v1, doc-annual-plan-v2, doc-execution-handbook, doc-kickoff-meeting, doc-venue-comparison |  |
| retrieve-budget-conflict | doc-annual-finance-review | doc-annual-finance-review, doc-annual-plan-v1, doc-annual-plan-v2, doc-execution-handbook, doc-kickoff-meeting, doc-publicity-plan |  |
| retrieve-content-related-publicity | doc-annual-plan-v1 | doc-annual-budget-draft, doc-annual-finance-review, doc-annual-plan-v1, doc-annual-plan-v2, doc-execution-handbook, doc-kickoff-meeting |  |
| retrieve-version-predecessor | doc-annual-plan-v1 | doc-annual-budget-draft, doc-annual-finance-review, doc-annual-plan-v1, doc-execution-handbook, doc-kickoff-meeting, doc-publicity-plan, doc-second-meeting, doc-venue-comparison |  |
| retrieve-long-document-neighbor | doc-annual-plan-v2 | doc-annual-budget-draft, doc-annual-finance-review, doc-annual-plan-v1, doc-annual-plan-v2, doc-kickoff-meeting, doc-publicity-plan, doc-second-meeting, doc-venue-comparison |  |
| retrieve-empty-isolated-document |  |  |  |

## 负例结果

| caseId | 结果 |
| --- | --- |
| negative-same-tags-different-project | filtered_before_model |
| negative-template-keyword-overlap | none |
| negative-isolated-maintenance | filtered_before_model |
| negative-venue-different-project | filtered_before_model |
| negative-publicity-template | filtered_before_model |

## 结论与下一步

固定 Fake 基线达到 Recall@8、证据有效率、非 none Precision、重复建议、自关联和跨空间关系质量门槛。Precision@8 微平均为 0.1707，说明无 Embedding 规则召回优先保证了覆盖，但仍有明显上下文噪声；该指标作为阶段 2 可选标签候选补充和后续混合召回的对照基线，不通过扩大 TopK 掩盖。下一开发切片进入可选标签的持久化基础、候选生成与审核后端，不在本报告中混入真实模型或前端结论。

## 解释与边界

本报告是固定资料上的 Fake 基线，不代表真实模型正确率。Fake 客户端只根据冻结标注选择关系类型、方向和精确 quote；真实候选召回、有限分片上下文、候选集合校验、逐字证据反查、MySQL 幂等和关系查询均由服务端执行。Precision@8 为 7 个召回用例的微平均，负例结果区分召回前过滤、模型明确 none 和误报。真实模型接入后必须在相同版本和计分范围下重新评估，并记录模型、供应商、参数、Token、耗时和失败样例。浏览器、真实模型与生产环境均不在本报告验证范围内。

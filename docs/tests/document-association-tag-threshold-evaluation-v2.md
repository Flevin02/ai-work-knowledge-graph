# 文档关联 confirmed 标签共同数量分层阈值评估 v2

- 基础资料集：document-association-eval-v1
- 标签阈值补充资料：document-association-tag-threshold-eval-v2
- 运行方式：Java 21 + MySQL + 冻结 expectedTags/补充标签作为 confirmed user 标签
- 候选策略：关闭标签使用 document-candidate-recall-v1；开启标签使用 document-candidate-recall-v3
- 单变量策略：内容通道未命中且共同 confirmed 标签数量至少为 2 个时才补充候选，并排在内容候选之后
- 对照：includeConfirmedTags=false/true，TopK 固定为 8
- 说明：本报告评估标签对候选召回的影响，不代表标签生成模型 Precision/Recall

## 汇总

| 指标 | 关闭标签 | 开启 confirmed 标签 |
| --- | ---: | ---: |
| Recall@8 | 0.8750 | 1.0000 |
| Precision@8 | 0.1707 | 0.1860 |
| 候选总数 | 41 | 43 |
| 命中硬负例 | 0 | 1 |
| confirmed 标签通道候选数 | 0 | 2 |
| 自关联候选 | 0 | 0 |
| 跨空间候选 | 0 | 0 |

## 用例明细

| 用例 | 默认候选 | 开启后候选 | 默认命中/硬负例 | 开启后命中/硬负例 | 标签通道数 |
| --- | --- | --- | ---: | ---: | ---: |
| retrieve-plan-from-explicit-reference | doc-second-meeting, doc-annual-plan-v1, doc-annual-plan-v2, doc-annual-budget-draft, doc-annual-finance-review, doc-venue-comparison, doc-publicity-plan, doc-execution-handbook | doc-second-meeting, doc-annual-plan-v1, doc-annual-plan-v2, doc-annual-budget-draft, doc-annual-finance-review, doc-venue-comparison, doc-publicity-plan, doc-execution-handbook | 1/0 | 1/0 | 0 |
| retrieve-meeting-history-and-support | doc-kickoff-meeting, doc-venue-comparison, doc-annual-plan-v2, doc-execution-handbook, doc-annual-plan-v1 | doc-kickoff-meeting, doc-venue-comparison, doc-annual-plan-v2, doc-execution-handbook, doc-annual-plan-v1 | 2/0 | 2/0 | 0 |
| retrieve-budget-conflict | doc-annual-finance-review, doc-kickoff-meeting, doc-annual-plan-v1, doc-annual-plan-v2, doc-publicity-plan, doc-execution-handbook | doc-annual-finance-review, doc-kickoff-meeting, doc-annual-plan-v1, doc-annual-plan-v2, doc-publicity-plan, doc-execution-handbook, doc-training-budget | 1/0 | 1/1 | 1 |
| retrieve-content-related-publicity | doc-execution-handbook, doc-annual-plan-v2, doc-annual-plan-v1, doc-annual-finance-review, doc-kickoff-meeting, doc-annual-budget-draft | doc-execution-handbook, doc-annual-plan-v2, doc-annual-plan-v1, doc-annual-finance-review, doc-kickoff-meeting, doc-annual-budget-draft, doc-venue-comparison | 1/0 | 2/0 | 1 |
| retrieve-version-predecessor | doc-annual-plan-v1, doc-kickoff-meeting, doc-second-meeting, doc-venue-comparison, doc-execution-handbook, doc-publicity-plan, doc-annual-finance-review, doc-annual-budget-draft | doc-annual-plan-v1, doc-kickoff-meeting, doc-second-meeting, doc-venue-comparison, doc-execution-handbook, doc-publicity-plan, doc-annual-finance-review, doc-annual-budget-draft | 1/0 | 1/0 | 0 |
| retrieve-long-document-neighbor | doc-annual-plan-v2, doc-annual-plan-v1, doc-publicity-plan, doc-annual-finance-review, doc-kickoff-meeting, doc-annual-budget-draft, doc-second-meeting, doc-venue-comparison | doc-annual-plan-v2, doc-annual-plan-v1, doc-publicity-plan, doc-annual-finance-review, doc-kickoff-meeting, doc-annual-budget-draft, doc-second-meeting, doc-venue-comparison | 1/0 | 1/0 | 0 |
| retrieve-empty-isolated-document |  |  | 0/0 | 0/0 | 0 |

## 结论与边界

补充资料同时加入了双共同标签正例和跨项目明确负例。开启标签后 Recall@8 从 0.8750 提升到 1.0000，Precision@8 从 0.1707 变化为 0.1860；标签通道补入 2 个候选，其中命中 1 个跨项目硬负例。该结果证明双共同标签可以补充内容漏召回正例，因此保留 v3 的数量阈值作为最低门槛；同时也证明数量阈值不能识别标签是否属于同一项目，不能把它当作关系判断或独立质量保障。

开启 confirmed 标签后，标签仍只作为候选召回信号；关系判断、逐字证据校验和人工审核由原有 Pipeline 执行，共同标签不能直接确认为关系。

本实验将冻结 expectedTags 和补充标签作为人工 confirmed 输入，未测试真实标签模型的抽取质量，也未在本实验中执行关系模型、证据校验或人工审核；浏览器入口、真实模型、生产代理和移动端另行验证。

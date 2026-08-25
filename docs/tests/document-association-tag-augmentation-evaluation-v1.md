# 文档关联 confirmed 标签仅补内容漏召回评估 v1

- 资料集：document-association-eval-v1
- 运行方式：Java 21 + SQLite + 冻结 expectedTags 作为 confirmed user 标签
- 候选策略：关闭标签使用 document-candidate-recall-v1；开启标签使用 document-candidate-recall-v2
- 单变量策略：confirmed 标签只补充所有默认内容通道均未命中的候选，并排在内容候选之后
- 对照：includeConfirmedTags=false/true，TopK 固定为 8
- 说明：本报告评估标签对候选召回的影响，不代表标签生成模型 Precision/Recall

## 汇总

| 指标 | 关闭标签 | 开启 confirmed 标签 |
| --- | ---: | ---: |
| Recall@8 | 1.0000 | 1.0000 |
| Precision@8 | 0.1707 | 0.1458 |
| 候选总数 | 41 | 48 |
| 命中硬负例 | 0 | 0 |
| confirmed 标签通道候选数 | 0 | 7 |
| 自关联候选 | 0 | 0 |
| 跨空间候选 | 0 | 0 |

## 用例明细

| 用例 | 默认候选 | 开启后候选 | 默认命中/硬负例 | 开启后命中/硬负例 | 标签通道数 |
| --- | --- | --- | ---: | ---: | ---: |
| retrieve-plan-from-explicit-reference | doc-second-meeting, doc-annual-plan-v1, doc-annual-plan-v2, doc-annual-budget-draft, doc-annual-finance-review, doc-venue-comparison, doc-publicity-plan, doc-execution-handbook | doc-second-meeting, doc-annual-plan-v1, doc-annual-plan-v2, doc-annual-budget-draft, doc-annual-finance-review, doc-venue-comparison, doc-publicity-plan, doc-execution-handbook | 1/0 | 1/0 | 0 |
| retrieve-meeting-history-and-support | doc-kickoff-meeting, doc-venue-comparison, doc-annual-plan-v2, doc-execution-handbook, doc-annual-plan-v1 | doc-kickoff-meeting, doc-venue-comparison, doc-annual-plan-v2, doc-execution-handbook, doc-annual-plan-v1, doc-annual-finance-review, doc-publicity-plan, doc-annual-budget-draft | 2/0 | 2/0 | 3 |
| retrieve-budget-conflict | doc-annual-finance-review, doc-kickoff-meeting, doc-annual-plan-v1, doc-annual-plan-v2, doc-publicity-plan, doc-execution-handbook | doc-annual-finance-review, doc-kickoff-meeting, doc-annual-plan-v1, doc-annual-plan-v2, doc-publicity-plan, doc-execution-handbook, doc-second-meeting, doc-venue-comparison | 1/0 | 1/0 | 2 |
| retrieve-content-related-publicity | doc-execution-handbook, doc-annual-plan-v2, doc-annual-plan-v1, doc-annual-finance-review, doc-kickoff-meeting, doc-annual-budget-draft | doc-execution-handbook, doc-annual-plan-v2, doc-annual-plan-v1, doc-annual-finance-review, doc-kickoff-meeting, doc-annual-budget-draft, doc-second-meeting, doc-venue-comparison | 1/0 | 1/0 | 2 |
| retrieve-version-predecessor | doc-annual-plan-v1, doc-kickoff-meeting, doc-second-meeting, doc-venue-comparison, doc-execution-handbook, doc-publicity-plan, doc-annual-finance-review, doc-annual-budget-draft | doc-annual-plan-v1, doc-kickoff-meeting, doc-second-meeting, doc-venue-comparison, doc-execution-handbook, doc-publicity-plan, doc-annual-finance-review, doc-annual-budget-draft | 1/0 | 1/0 | 0 |
| retrieve-long-document-neighbor | doc-annual-plan-v2, doc-annual-plan-v1, doc-publicity-plan, doc-annual-finance-review, doc-kickoff-meeting, doc-annual-budget-draft, doc-second-meeting, doc-venue-comparison | doc-annual-plan-v2, doc-annual-plan-v1, doc-publicity-plan, doc-annual-finance-review, doc-kickoff-meeting, doc-annual-budget-draft, doc-second-meeting, doc-venue-comparison | 1/0 | 1/0 | 0 |
| retrieve-empty-isolated-document |  |  | 0/0 | 0/0 | 0 |

## 结论与边界

本策略恢复了默认内容候选的稳定顺序，并把 confirmed 标签通道统计收敛为真正仅由标签补充的候选；但候选总数仍为 48，Precision@8 仍为 0.1458，与上一轮未降噪开关对照一致。原因是部分内容召回不足 8 条时，单个宽泛共同标签仍会填满剩余名额，因此该策略不能称为候选质量提升。下一单变量实验应评估共同标签数量分层阈值。

开启 confirmed 标签后，标签仍只作为候选召回信号；关系判断、逐字证据校验和人工审核由原有 Pipeline 执行，共同标签不能直接确认为关系。

本实验将冻结 expectedTags 作为人工 confirmed 输入，未测试真实标签模型的抽取质量；浏览器入口、真实模型、生产代理和移动端另行验证。

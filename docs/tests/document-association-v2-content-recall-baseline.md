# v2 内容召回基线报告（document-candidate-recall-v1）

- datasetVersion：document-association-eval-v2
- 候选召回：document-candidate-recall-v1，TopK=8，无 Embedding
- 运行方式：Java 21 + MySQL + 确定性词面规则

## 指标

| 指标 | 结果 |
| --- | ---: |
| 期望候选总数 | 9 |
| 内容臂命中 | 3 |
| 内容臂 Recall@8 | 0.3333 |
| 内容候选总数 | 10 |

## 用例明细

| caseId | 期望 | 实际 | 命中 | 漏掉 | 词面预期 |
| --- | --- | --- | --- | --- | --- |
| v2-retrieval-kb-plan | v2-doc-archive-requirement, v2-doc-dashboard-design | v2-doc-answer-launch, v2-doc-dashboard-design | v2-doc-dashboard-design | v2-doc-archive-requirement | partial |
| v2-retrieval-archive-requirement | v2-doc-kb-plan | v2-doc-answer-launch |  | v2-doc-kb-plan | missed |
| v2-retrieval-crm-integration | v2-doc-crm-upgrade |  |  | v2-doc-crm-upgrade | missed |
| v2-retrieval-crm-upgrade | v2-doc-crm-integration | v2-doc-dashboard-design, v2-doc-robot-procurement |  | v2-doc-crm-integration | missed |
| v2-retrieval-october-meeting | v2-doc-answer-launch |  |  | v2-doc-answer-launch | missed |
| v2-retrieval-answer-launch | v2-doc-october-meeting | v2-doc-archive-requirement, v2-doc-kb-plan |  | v2-doc-october-meeting | missed |
| v2-retrieval-dashboard-design | v2-doc-dashboard-review, v2-doc-kb-plan | v2-doc-crm-upgrade, v2-doc-dashboard-review, v2-doc-kb-plan | v2-doc-dashboard-review, v2-doc-kb-plan |  | recalled |
| v2-retrieval-isolated-coffee |  |  |  |  | empty |

## 结论

v2 冻结的词面设计契约成立：6 个期望候选被内容通道漏掉（同义改写、中英缩写、口语对正式三类场景），内容可召回对照组保持命中，孤立文档空召回。这些漏召回正例正是语义召回补充价值的测量空间。

# v2 语义召回对照实验报告 v1（真实 qwen3.7-text-embedding）

- datasetVersion：document-association-eval-v2
- 内容候选召回：document-candidate-recall-v1，TopK=8
- 语义候选召回：document-semantic-recall-v1，文档级 TopK=8，分片查询 TopK=8
- 融合方式：RRF，constant=60，TopK=8
- Embedding：真实 OpenAI-compatible（EmbeddingModelDescriptor[provider=openai-compatible, model=qwen3.7-text-embedding, version=dashscope-qwen3.7-embedding-v1, dimension=1024]）
- 运行方式：Java 21 + MySQL + 真实 Embedding + 精确 COSINE 扫描

## 指标

| 指标 | 内容臂 | 语义臂 | RRF 融合臂 |
| --- | ---: | ---: | ---: |
| Recall@8（微平均） | 0.3333 | 记录见用例明细 | 1.0000 |
| 候选总数 | 10 | 见用例明细 | 49 |
| 硬负例命中 | 0 | 见用例明细 | 2 |
| 孤立文档语义候选 | - | 5 | - |

## 漏召回补充

- 漏召回用例期望候选总数：5
- 语义臂补上的漏召回正例数：5
- 融合臂 Recall@8 相对内容臂变化：0.6667
- 融合臂 Precision@8：0.1837（内容臂对照 0.1707 量级，v2 上内容臂基线更低）

## 用例明细

| caseId | 期望 | 内容候选 | 语义候选 | 融合候选 |
| --- | --- | --- | --- | --- |
| v2-retrieval-kb-plan | v2-doc-archive-requirement, v2-doc-dashboard-design | v2-doc-answer-launch, v2-doc-dashboard-design | v2-doc-answer-launch, v2-doc-archive-requirement, v2-doc-crm-integration, v2-doc-crm-upgrade, v2-doc-dashboard-design, v2-doc-dashboard-review, v2-doc-october-meeting, v2-doc-phase-one-acceptance | v2-doc-answer-launch, v2-doc-archive-requirement, v2-doc-crm-integration, v2-doc-crm-upgrade, v2-doc-dashboard-design, v2-doc-dashboard-review, v2-doc-october-meeting, v2-doc-phase-one-acceptance |
| v2-retrieval-archive-requirement | v2-doc-kb-plan | v2-doc-answer-launch | v2-doc-answer-launch, v2-doc-crm-integration, v2-doc-crm-upgrade, v2-doc-kb-plan, v2-doc-october-meeting, v2-doc-phase-one-acceptance | v2-doc-answer-launch, v2-doc-crm-integration, v2-doc-crm-upgrade, v2-doc-kb-plan, v2-doc-october-meeting, v2-doc-phase-one-acceptance |
| v2-retrieval-crm-integration | v2-doc-crm-upgrade |  | v2-doc-answer-launch, v2-doc-archive-requirement, v2-doc-crm-upgrade, v2-doc-dashboard-design, v2-doc-kb-plan, v2-doc-october-meeting | v2-doc-answer-launch, v2-doc-archive-requirement, v2-doc-crm-upgrade, v2-doc-dashboard-design, v2-doc-kb-plan, v2-doc-october-meeting |
| v2-retrieval-crm-upgrade | v2-doc-crm-integration | v2-doc-dashboard-design, v2-doc-robot-procurement | v2-doc-archive-requirement, v2-doc-crm-integration, v2-doc-dashboard-design, v2-doc-dashboard-review, v2-doc-kb-plan, v2-doc-october-meeting, v2-doc-phase-one-acceptance, v2-doc-robot-procurement | v2-doc-archive-requirement, v2-doc-crm-integration, v2-doc-dashboard-design, v2-doc-dashboard-review, v2-doc-kb-plan, v2-doc-october-meeting, v2-doc-phase-one-acceptance, v2-doc-robot-procurement |
| v2-retrieval-october-meeting | v2-doc-answer-launch |  | v2-doc-answer-launch, v2-doc-archive-requirement, v2-doc-crm-upgrade | v2-doc-answer-launch, v2-doc-archive-requirement, v2-doc-crm-upgrade |
| v2-retrieval-answer-launch | v2-doc-october-meeting | v2-doc-archive-requirement, v2-doc-kb-plan | v2-doc-archive-requirement, v2-doc-crm-integration, v2-doc-crm-upgrade, v2-doc-dashboard-design, v2-doc-dashboard-review, v2-doc-kb-plan, v2-doc-october-meeting | v2-doc-archive-requirement, v2-doc-crm-integration, v2-doc-crm-upgrade, v2-doc-dashboard-design, v2-doc-dashboard-review, v2-doc-kb-plan, v2-doc-october-meeting |
| v2-retrieval-dashboard-design | v2-doc-dashboard-review, v2-doc-kb-plan | v2-doc-crm-upgrade, v2-doc-dashboard-review, v2-doc-kb-plan | v2-doc-answer-launch, v2-doc-crm-integration, v2-doc-crm-upgrade, v2-doc-dashboard-review, v2-doc-kb-plan, v2-doc-phase-one-acceptance | v2-doc-answer-launch, v2-doc-crm-integration, v2-doc-crm-upgrade, v2-doc-dashboard-review, v2-doc-kb-plan, v2-doc-phase-one-acceptance |
| v2-retrieval-isolated-coffee |  |  | v2-doc-answer-launch, v2-doc-archive-requirement, v2-doc-kb-plan, v2-doc-october-meeting, v2-doc-robot-procurement | v2-doc-answer-launch, v2-doc-archive-requirement, v2-doc-kb-plan, v2-doc-october-meeting, v2-doc-robot-procurement |

## 结论与边界

语义臂在词面零重叠的漏召回用例上补回了 5 个期望候选，证明真实 Embedding 能捕获内容通道无法覆盖的语义关联。本报告只回答召回层问题；关系判断、证据校验与人工审核不在本实验范围内。结论仅适用于当次端点、模型、版本和 v2 固定资料集，更换任一变量后必须重新评估。

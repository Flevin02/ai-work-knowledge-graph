# 文档关联固定评估规程 v1

## 1. 目标

本规程用于验证“无标签、无 Embedding”的第一版文档内容候选召回与关系判断。资料和答案位于 fixture/document-association-v1，全部为虚构内容。

本规程只证明固定资料集上的可重复结果，不代表真实公司资料、真实模型、生产代理或多实例部署质量。

## 2. 冻结版本

- 资料集：document-association-eval-v1
- 文档关联 Prompt：document-association-v1
- 文档关联 Schema：document-association-v1
- 候选召回策略：document-candidate-recall-v1
- 文档关联策略：document-association-policy-v1
- 标签 Prompt：document-tag-v1，阶段 2 使用
- 标签 Schema：document-tag-v1，阶段 2 使用

实验运行必须同时记录这些版本。只改变其中一个主要变量后才能比较结果。

## 3. 资料覆盖

资料集包含 12 份文档：

- 7 条正例关系，覆盖 related_to、references、supports、updates 和 conflicts_with。
- 5 组明确负例，覆盖共同标签但不同项目、模板关键词重合、孤立文档和场地金额误匹配。
- 1 份 Markdown 表格文档。
- 1 份足以触发多分片验证的长文档。
- 1 组同一逻辑文档 v1/v2 版本变化。
- 1 个重复导入场景。

只有 annotations.json 中列出的 expectedRelations、negativePairs 和 retrievalCases 参与 v1 计分。未列出的文档对保持未标注状态，不自动计为负例。

## 4. 阶段 0 静态验收

在写产品代码前必须满足：

1. annotations.json 是合法 JSON。
2. documentId、relationId 和 caseId 在各自范围内唯一。
3. 每个标注文档路径存在，kind 与扩展名一致。
4. 每个 expectedTags、expectedRelations 和 negativePairs 的 quote 都能在对应文档逐字查到。
5. 每条正例关系只使用五种白名单类型。
6. 对称关系没有反向重复，有向关系的主体和客体明确。
7. expectedRelations 与 negativePairs 不包含同一文档对的矛盾答案。
8. retrievalCases 的期望候选和硬负例都引用真实文档，且不包含当前文档自身。
9. 表格、长文档、重复导入、版本变化和孤立文档均有明确标识。

仓库级重复验证命令为 `node scripts/validate-document-association-fixture.mjs`；该脚本无第三方依赖，只读取固定 fixture 和设计 Schema，并检查 Schema 的 Draft 版本、v1 标识、关系/方向枚举和顶层必填字段。

## 5. 阶段 1 候选召回评估

对每个 retrievalCases 项执行一次候选召回，固定 TopK=8：

- Recall@8 = 进入前 8 的期望候选数 / 期望候选总数。
- Precision@8 = 前 8 中属于该用例期望候选的数量 / 实际返回候选数量。
- 空召回用例单独统计：孤立文档应返回空候选，不因共同部门词汇产生关系候选。
- hardNegativeDocumentIds 用于定位高风险误召回，但只要进入模型比较阶段就记录失败样例，不能在报告中隐藏。

固定资料集的 Recall@8 目标不低于 90%。Precision@8 用于比较策略噪声，不通过盲目扩大 TopK 提高 Recall。

## 6. 阶段 1 关系判断评估

模型只能对服务端候选列表逐一输出五种关系或 none：

- 关系类型准确率 = 正确关系类型数 / 全部正例关系数。
- 方向准确率 = 方向正确的有向关系数 / 全部有向正例关系数。
- 非 none Precision = 正确非 none 建议数 / 全部非 none 建议数。
- 证据有效率 = 可在正确文档和分片逐字反查的证据数 / 全部输出证据数。
- 无依据建议率 = 缺少有效证据仍输出非 none 的建议数 / 全部非 none 建议数。
- 重复建议率 = 同一规范化关系键的重复建议数 / 全部非 none 建议数。

第一版质量门槛：证据有效率 100%，跨空间建议 0，自关联 0，重复建议 0，非 none Precision 不低于 80%。未达到门槛时优先修复解析、候选过滤、关系规则和证据校验，不通过放宽 Schema 掩盖问题。

## 7. 阶段 2 标签评估

expectedTags 用于可选标签阶段，不阻塞阶段 1：

- 统计标签 Precision、Recall 和 F1。
- AI 标签必须有逐字证据且初始状态为 suggested。
- 共同标签只能补充候选，不能单独把关系判断为非 none。
- 标签 Precision 目标不低于 85%。

## 8. 重复、版本和恢复场景

- 将 doc-publicity-plan 的同一文件连续导入两次，同一知识空间只应有一份有效来源记录。
- doc-annual-plan-v2 与 doc-annual-plan-v1 共享 logicalDocumentKey，预期关系为 updates；旧关系和审核历史不能被静默覆盖。
- doc-execution-handbook 应经过多分片处理，证据仍需定位到正确分片。
- doc-printer-maintenance 的候选召回为空属于正常完成，不记录 association_model_failed。

## 9. 实验记录模板

每次实验至少记录：

- 运行时间和 Git 提交。
- datasetVersion、Prompt、Schema、候选召回和关联策略版本。
- 聊天模型、供应商、温度、超时、重试和最大输出。
- TopK、每候选分片数和上下文预算。
- Recall@8、Precision@8、关系类型准确率、方向准确率、证据有效率、非 none Precision、Token、耗时和失败率。
- 所有 hard negative、none、证据失败和结构化输出失败样例。
- 本轮唯一改变的主要变量、结论和下一步。

Fake AI、真实模型、浏览器、本地 SQLite 和生产验证必须分栏记录，不能互相替代。

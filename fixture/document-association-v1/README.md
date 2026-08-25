# 文档关联固定评估资料 v1

本目录是专项阶段 0 冻结的完全虚构资料集，版本固定为 document-association-eval-v1。它只用于文档候选召回、五种关系判断、证据校验、可选标签和后续 RAG 对比实验，不代表生产数据。

## 内容

- documents/：12 份可独立导入的 UTF-8 Markdown/TXT 文档。
- annotations.json：机器可读的文档、标签、正例关系、负例关系和特殊场景标注。
- tag-threshold-cases-v2.json：不改写 v1 基线的双共同标签补充标注，包含同项目正例、跨项目明确负例及逐字证据。

## 覆盖范围

- 五种白名单关系：related_to、references、supports、updates、conflicts_with。
- 共同标签但不同项目、模板文本相似但无业务关系、完全孤立资料。
- Markdown 表格、长文档、多分片、重复导入和同一逻辑文档版本变化。
- 每条计分标签和关系都包含可在原文件中逐字查找的证据。

## 计分边界

v1 只对 annotations.json 中 expectedRelations、negativePairs 和 retrievalCases 明确列出的样本计分。未列出的文档对不自动视为负例，也不允许实验过程中临时修改答案来适配模型输出。

所有实验必须记录资料集、Prompt、Schema、候选召回策略和关联策略版本。改变任何标注文档正文或答案时必须升级资料集版本，不能静默覆盖 v1。

`document-association-tag-threshold-eval-v2` 只追加人工 confirmed 标签和阈值评估用例，基础正文、`annotations.json` 及 v1 历史报告保持冻结；它不代表真实标签模型质量。

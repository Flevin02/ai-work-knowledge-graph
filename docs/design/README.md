# 技术设计文档

这里记录实现级技术设计、数据模型、接口契约、调用流程和交互细节。产品范围与架构边界以 `../prd/ai-work-knowledge-graph-maintainer-prd.md` 为准；本目录不记录下一任务、提交历史或验证结论。

- [有据问答生产客户端 v1](./conversation-answer-client-v1.md)：OpenAI-compatible 适配、Prompt/Schema、失败分类、配置与回滚边界。
- [Ollama + qwen3-embedding 本地部署交接](./local-ollama-qwen3-embedding-deployment.md)：本机 Embedding 服务安装、自检、后端配置和向量版本隔离边界。

## AI/RAG 分片基线

文档处理使用“章节感知分片 + 逐片结构化抽取”，分片数量由来源资料内容决定，不固定为某个片数：

- Markdown 先由确定性规则识别标题层级和章节路径；没有标题的 TXT/PDF 作为一个根章节处理。
- 长度不超过 `maxChunkChars` 的章节整体保留，只有超长章节才继续切分。
- 超长章节优先在换行位置结束，避免切断列表项或段落；相邻分片保留 `overlapChars` 字符重叠，降低关系或句子跨边界时的上下文损失。
- 当前默认 `maxChunkChars=1500`、`overlapChars=150`，可通过 `AI_RAG_MAX_CHUNK_CHARS` 和 `AI_RAG_OVERLAP_CHARS` 调整；这两个值是可复现实验基线，不是已经证明的最优参数。

选择章节感知分片的原因是同时控制模型上下文长度、单次调用成本和证据定位难度。分片过小会增加请求次数、重复实体和跨片关系合并成本；分片过大则会增加上下文噪声、超出模型限制和证据反查复杂度。固定字符数只作为长章节的边界约束，不能替代章节、表格、列表和代码块等语义边界。

同一分片事实供结构化抽取、Embedding、语义候选召回和有据问答复用；MySQL 保存章节、分片、内容指纹和可重建向量事实，固定资料规模由 Java 执行精确 COSINE。分片本身不等于 RAG 完成，仍需分别评估解析、召回、上下文组装、生成和证据校验。

## 文档关联阶段 0 冻结基线

- [文档关联 v1 冻结契约](./document-association-v1-contract.md)：候选召回、五种关系、Prompt、Schema、方向和证据规则。
- `document-association-output-schema-v1.json`：阶段 1 文档关联模型输出 JSON Schema。
- `document-tag-output-schema-v1.json`：阶段 2 可选标签模型输出 JSON Schema。

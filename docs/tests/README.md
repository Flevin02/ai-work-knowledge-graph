# 测试与评估

本目录只记录测试方案、固定数据集、指标口径、验证结果和未覆盖边界，不承担产品设计或下一任务规划。实现完成历史由 Git 追溯。

文档内容关联专项使用 [document-association-evaluation-v1.md](./document-association-evaluation-v1.md) 和 `fixture/document-association-v1/annotations.json` 作为固定评估规程与标注答案；其余报告分别保存对应实验的输入、单一变量、结果和失败样例。

- [有据问答生产客户端 v1 验证](./conversation-answer-client-v1.md)：记录生产适配器、失败分类、Fake/MySQL 回归和真实 AI 未验证边界。
- [有据问答前端最小闭环验证](./conversation-frontend-minimal-loop-2026-09-02.md)：记录问答前端客户端、面板、工作台入口、本机 HTTP 联调和浏览器未覆盖边界。
- [Ollama qwen3-embedding 本地部署验证](./local-ollama-qwen3-embedding-deployment-20260902.md)：记录本机 Ollama 服务、OpenAI-compatible Embedding 协议、项目 Java 客户端 smoke 和未覆盖边界。
- [Ollama qwen3-embedding 语义召回 RRF 评估](./document-association-semantic-rrf-evaluation-real-ollama-qwen3-embedding-v1.md)：记录固定资料集上的内容臂、语义臂和 RRF 融合臂指标；结论是不接入默认候选链路。

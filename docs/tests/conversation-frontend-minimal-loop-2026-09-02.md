# 有据问答前端最小闭环验证报告

验证日期：2026-09-02

## 1. 验证对象

- 前端会话、消息和引用 TypeScript 契约及 API 客户端。
- 有据问答面板的单文档范围创建、会话恢复、提交问题、回答状态和引用展示。
- 工作台“有据问答”入口、URL 会话恢复和已验证引用打开来源资料。
- `AI_ENABLED=false`、`AI_EMBEDDING_ENABLED=false` 时的本机前后端最小联调边界。

## 2. 自动测试

执行：

```bash
cd frontend
npm test
```

结果：3 个测试文件、8 项测试通过，0 失败。

覆盖结论：

- API 客户端请求路径与后端 `/api/v1/spaces/{spaceId}/conversations` 资源一致。
- 会话、消息、引用和 Snowflake `Long` 标识在前端均按字符串处理。
- 面板可创建单文档范围会话、展示用户问题和助手回答。
- `grounded`、`insufficient_evidence`、`failed` 和 `partially_grounded` 均有明确展示。
- `verified` 引用可以触发打开回调；`stale` 引用禁用并提示来源版本变化。
- 工作台可从 `view=conversation&conversationId=...` 恢复问答视图，并保持当前知识空间隔离。

## 3. 前端构建验证

执行：

```bash
cd frontend
npm run typecheck
npm run build
```

结果：

- TypeScript 严格检查通过。
- Next.js 16.3.1 / Turbopack 生产构建通过。

## 4. Java 21 后端回归

执行：

```bash
cd backend
kg_java_home=$(/usr/libexec/java_home -v 21)
JAVA_HOME="$kg_java_home" PATH="$kg_java_home/bin:$PATH" mvn -pl server -am test
```

结果：139 项测试通过，0 失败，0 错误，0 跳过。

该结果证明当前问答后端契约、关闭降级、Fake/MySQL/MockMvc 回归仍然通过；未启用 `real-ai` Profile。

## 5. 本机 HTTP 联调

启动方式：

```bash
cd backend/server
kg_java_home=$(/usr/libexec/java_home -v 21)
AI_ENABLED=false AI_EMBEDDING_ENABLED=false JAVA_HOME="$kg_java_home" PATH="$kg_java_home/bin:$PATH" mvn spring-boot:run -DskipTests
```

```bash
cd frontend
npm run dev
```

联调数据：

- 知识空间：本机测试空间 `220732143033126912`。
- 来源资料：`fixture/annual-party/01-年会活动方案.md`，导入后文档标识 `220732143360282624`。
- 会话：`220732350789586944`，恢复结果中的 `scopeDocumentId` 为 `220732143360282624`。
- 前端 URL：`http://localhost:3010/?spaceId=220732143033126912&view=conversation&conversationId=220732350789586944` 返回 HTTP 200。

提交问题“年会场地在哪里？”后，后端在 AI 关闭状态下返回：

```json
{
  "status": "failed",
  "groundingStatus": "",
  "errorCategory": "answer_client_unavailable",
  "errorMessage": "有据问答服务未启用",
  "citationCount": 0
}
```

覆盖结论：

- 前端可访问真实问答 URL，不依赖演示回答兜底。
- 单文档范围会话能够按空间恢复，且范围文档 ID 未丢失。
- AI 关闭时返回稳定失败类别和用户可读错误摘要，不会伪装成有证据回答。

## 6. 未覆盖边界

- 当前会话的浏览器控制发现结果为空，未完成真实可见点击验收；HTTP 200、Vitest 和构建结果不等同于浏览器验收通过。
- 未启用真实 AI，不证明 API Key、Base URL、模型权限、真实回答质量、费用或延迟。
- 未启用、未修改、未验证 Embedding、Ollama、Milvus、Reranker、SSE、断线续传、会话列表管理或 Agent。
- 本机联调新增了虚构测试空间和文档记录，未执行删除、重置数据库或清理上传文件。

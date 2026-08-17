# AGENTS.md

本文件适用于 `/Users/flevin/projects/ai-work-knowledge-graph` 整个项目，用于约束后续 Codex/LLM 会话的分析、编码、验证、文档和 Git 行为。

## 1. 交流约定

- 始终使用中文回答。
- 每次回复用户时，称呼用户为“ **ฅ՞•ﻌ•՞ฅ 付付大王ᐝ** ”。
- 先说明实际结果、当前边界和下一步，再补充实现细节。
- 不掩饰未实现、未测试、未联调或仅使用演示数据的部分。
- 遇到需求歧义、数据安全、模型供应商或架构边界变化时，先说明理解和影响，再实施。

## 2. 项目目标

本项目是独立的参赛作品“AI 工作知识图谱维护助手”（暂定名“知脉”），目标是：

> 自动理解散落的办公资料，建立有证据、可追溯、可持续更新的工作知识图谱。

核心主线固定为：

```text
导入资料
  → AI 提取实体、关系和证据
  → 人工审核关系
  → 交互式图谱
  → 节点详情和来源追溯
  → 健康检查
  → 增量维护
  → Markdown / JSON / 图谱图片导出
```

完整度优先于功能数量。核心链路稳定前，不扩张到海报、周报、多用户、复杂权限、消息推送或重型知识库平台。

## 3. 项目隔离边界

- 本项目使用独立 Git 仓库和独立运行环境。
- 不依赖 EIS 源码、数据库、配置、服务或部署环境。
- 不直接读取 `/Users/flevin/Documents/docs` 下的个人或工作资料。
- 不通过本机绝对路径依赖 `/Users/flevin/projects/firefly-boot`。
- Firefly Boot 只作为脚手架参考；复制后的代码由当前项目独立维护。
- 不复制 Firefly Boot 工作区的未提交修改、示例调试代码或本地敏感配置。
- `fixtures/` 只允许存放虚构或完成脱敏的演示资料。

## 4. 目录与职责

```text
ai-work-knowledge-graph/
├── app/                 # Next.js 页面入口和全局样式
├── src/                 # 前端组件、前端类型和演示数据
├── backend/
│   ├── common/          # 统一响应、异常、TraceId 等技术能力
│   └── server/          # 文档、图谱、审核、健康检查和 AI 业务
├── docs/
│   ├── prd/             # 产品需求、实施状态和新会话代办
│   ├── design/          # 技术设计和数据模型
│   └── decisions/       # 已确认设计决策
├── fixtures/            # 虚构演示资料
└── tests/               # 跨模块或端到端测试说明
```

- `common` 只放真正跨业务复用的技术能力。
- 图谱、文档、审核和 AI 领域代码全部放在 `server`。
- 不为每个功能新增 Maven 模块，不把后端扩张成微服务。

## 5. 技术基线

### 前端

- Next.js 15.5.23
- React 18
- TypeScript 严格模式
- Cytoscape.js
- CSS 工作台界面

### 后端

- Java 21
- Spring Boot 3.2.11
- Maven `common + server` 两模块
- Spring Web / Validation / JDBC
- SQLite JDBC
- Knife4j / SpringDoc OpenAPI
- Apache POI / PDFBox

### AI

- 领域层依赖 `AiExtractionClient` 抽象，不依赖具体供应商对象。
- Gemini 是默认实现，不是固定产品依赖。
- 模型、供应商、Base URL 和密钥必须配置化。
- AI 只生成候选实体、关系、证据和冲突；正式关系必须经过人工确认。

## 6. 新会话启动流程

每次新会话开始时，依次执行：

1. 阅读本 `AGENTS.md`。
2. 阅读 `docs/prd/ai-work-knowledge-graph-maintainer-prd.md` 第 18 节“当前实施状态”。
3. 阅读 PRD 第 19 节“下一步代办与新会话入口”。
4. 阅读 `docs/roadmap.md`，确认当前阶段边界。
5. 执行 `git status --short --branch` 和 `git log --oneline -5`。
6. 检查当前工作区是否存在用户或上一会话遗留的未提交修改。
7. 只从 PRD 中记录的第一优先级继续，不重新搭建已完成的脚手架。

## 7. 编码原则

### 通用原则

- 修改前先阅读调用链、数据流和现有约定。
- 只写完成当前任务所需的最少代码。
- 不顺手重构、格式化或删除无关代码。
- 发现无关历史问题时可以记录，但不要扩大当前修改范围。
- 每一行修改都应能追溯到当前需求或必要验证。
- 正常态、空态、失败态和恢复路径应一起考虑。

### Java 分层

- Controller 只接收参数、调用 Service、返回 `ApiResponse<T>`。
- Controller 接口说明写在 mapping 的 `name` 属性中。
- Service 接口和实现方法必须有 Javadoc，说明用途、参数和返回语义。
- Repository/DAO 方法必须有 Javadoc，说明数据目的和返回语义。
- 业务规则放在 Service，不下沉到 Controller。
- 循环查询数据库前先评估批量查询和内存映射。
- 方法参数超过 2 个或单行影响可读性时，每行放一个参数，右括号单独换行。
- 方法体内调用其他方法时，在调用语句正上方增加说明本次调用业务目的的独立行注释。
- 异常必须保留有助于定位问题的上下文，不吞异常，不把正常的 404 记录成系统 ERROR。

### 前端

- 前端不保存模型密钥、数据库路径或服务端敏感配置。
- 图谱组件只负责展示和交互，数据导入、解析和持久化由 Java 后端负责。
- 前后端契约变化时同步更新 TypeScript 类型和 Java DTO。
- 默认使用聚焦图和类型筛选，避免全局图谱形成不可读的“毛线团”。
- 前端演示数据必须明确标识，真实接口接入后不得静默继续使用演示数据兜底。

## 8. API 与 Knife4j 规范

- 统一前缀由 `server.servlet.context-path` 配置，默认 `/api`。
- Controller 不重复声明 `/api`，只声明 `/health`、`/v1/graph` 等业务路径。
- Controller 类使用 `@Tag` 描述业务分组。
- 接口方法使用 `@Operation` 描述摘要和用途。
- 请求 DTO、响应 DTO 和字段使用 `@Schema` 提供中文说明及必要示例。
- 参数需要额外语义时使用 `@Parameter`。
- 不为普通接口重复添加 `@ApiResponses`；响应结构由 SpringDoc 根据真实泛型返回类型推导。
- 统一响应使用 `ApiResponse<T>`，并保留 `traceId`。
- 新接口完成后至少检查 OpenAPI JSON 或 Knife4j 页面中的路径、标签、摘要和具体响应模型。

## 9. 数据与安全

- API Key、Token、密码、连接串等只通过环境变量或未提交的本地配置注入。
- `.env.example` 只放变量名和空值/安全示例，不放真实密钥。
- 提交前扫描源码、配置、文档和演示资料中的敏感信息。
- SQLite 数据库、上传文件、构建产物、IDE 配置不提交 Git。
- 文件删除优先软删除或失效标记，不直接删除事实来源。
- 原始办公资料是事实源；图谱节点和关系只保存结构化索引、摘要和证据定位。
- AI 建议必须携带来源和证据，不能仅凭文件名或关键词建立正式关系。

## 10. 验证要求

### 前端

```bash
npm run typecheck
npm run build
```

### Java 后端

本项目必须显式使用 Java 21。本机默认 Maven 可能运行在其他 JDK：

```bash
kg_java_home=$(/usr/libexec/java_home -v 21)
JAVA_HOME="$kg_java_home" PATH="$kg_java_home/bin:$PATH" mvn test
```

- 后端共享模块变更使用根 Reactor 测试。
- 接口、异常处理或数据规则变更应增加针对性测试。
- `test-compile`、构建成功或服务启动不等于真实数据、模型或生产环境联调完成。
- 最终汇报必须区分：编译、自动测试、本地接口、真实外部服务和生产部署分别证明了什么。

## 11. PRD 维护与会话交接

PRD 是产品范围、当前状态和下一会话入口的正式事实记录：

`docs/prd/ai-work-knowledge-graph-maintainer-prd.md`

- 已实现并验证的能力写入第 18.1 节。
- 仅有骨架、演示数据或尚未联调的部分写入第 18.2 节。
- 下一次新会话可直接执行的首要任务写入第 19.1 节。
- 后续主线写入第 19.2 节。
- 不把计划描述成已完成，不把本地接口验证描述成真实模型或生产联调。

## 12. Git 提交策略

### 小步提交

- 采用频繁、小范围、可回滚的提交策略。
- 一个独立问题修复、接口链路、数据表及其分层、相关测试或稳定架构边界完成后，可以提交。
- 不等待多个无关功能堆积后再提交。
- 一个提交只表达一个主要目的；无关改动拆分提交。

### 每次提交前必须执行

1. 检查 `git status --short --branch`。
2. 检查实际 staged/unstaged diff，确认没有无关文件。
3. 先更新 PRD 的版本、状态、已完成、验证边界和下一步代办。
4. 执行与本次改动相匹配的测试或构建。
5. 执行 `git diff --check`。
6. 扫描密钥、Token、密码、连接串和真实个人/公司数据。
7. 确认 `node_modules`、`.next`、`target`、数据库、上传文件和 IDE 配置未进入提交。
8. 根据实际 diff 生成中文 Conventional Commit 信息。

### 提交信息

使用：

```text
<type>(<scope>): <中文摘要>

- <具体改动>
- <具体改动>
- <具体改动>
```

允许的常用类型：`feat`、`fix`、`refactor`、`docs`、`test`、`chore`、`build`。

### 推送边界

- 用户允许 Codex在形成必要阶段成果时主动进行本地提交。
- 本地提交不等于推送。
- 未经用户明确说“推送”，不得执行 `git push`。
- 禁止擅自 force push、改写远程历史或删除远程分支。
- 推送前确认本地分支和 `origin/main` 的领先/落后关系。

## 13. 当前远程仓库

```text
origin = https://github.com/Flevin02/ai-work-knowledge-graph.git
```

远程地址仅用于当前独立项目，不与 EIS 或 Firefly Boot 仓库混用。

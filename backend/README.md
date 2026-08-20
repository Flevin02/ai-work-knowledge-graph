# 后端服务

这里承载知识图谱维护助手的 Java 服务端能力，与前端工作台分离。

## 职责边界

- 文件接收、解析和内容指纹。
- SQLite 数据持久化；业务 CRUD 和查询统一使用 MyBatis-Plus/MyBatis Mapper。
- LangChain4j OpenAI-compatible 模型调用和结构化输出校验。
- 实体规范化、关系去重、证据保存和冲突检测。
- 图谱查询、关系审核和 Markdown/JSON 导出。
- API Key、数据库路径和上传目录等服务端配置。

## 当前状态

后端基于 `/Users/flevin/projects/firefly-boot/backend` 脚手架选择性复用，采用 Java 21、Spring Boot 3.2.11、`common + server` 两模块、MyBatis-Plus 3.5.17 和 SQLite。复制后在当前仓库独立维护，不通过本机路径依赖原工程。

后端重构后将由 `server` 模块提供启动类和业务接口：

```bash
cd backend
kg_java_home=$(/usr/libexec/java_home -v 21)
JAVA_HOME="$kg_java_home" PATH="$kg_java_home/bin:$PATH" mvn test
```

正式启动请从仓库根目录执行：

```bash
cd ..
mvn -f backend/pom.xml -pl server -am spring-boot:run
```

默认地址：`http://localhost:4010`

统一前缀由 `server.servlet.context-path` 配置，默认值为 `/api`：

- `GET /api/health`
- `GET /api/v1/spaces`
- `POST /api/v1/spaces`
- `DELETE /api/v1/spaces/{spaceId}`
- `GET /api/v1/spaces/{spaceId}/documents`
- `POST /api/v1/spaces/{spaceId}/documents`（multipart 字段：可选 `documentType`、`files`）
- `GET /api/v1/spaces/{spaceId}/documents/{documentId}/content`
- `GET /api/v1/spaces/{spaceId}/documents/{documentId}/extractions`
- `POST /api/v1/spaces/{spaceId}/documents/{documentId}/extractions`
- `GET /api/v1/spaces/{spaceId}/documents/{documentId}/extractions/{extractionId}`
- `DELETE /api/v1/spaces/{spaceId}/documents/{documentId}`
- `GET /api/v1/spaces/{spaceId}/graph`
- `GET /api/v1/spaces/{spaceId}/graph/summary`

Controller 内部不重复声明 `/api`，后续切换部署前缀只修改 `SERVER_CONTEXT_PATH`。

## SQLite 与来源资料目录

正式运行数据库和上传目录默认固定到 `backend/server/data/`；如果从其他工作目录启动，也必须让以下三个变量指向同一目录：

```properties
DATA_DIR=./backend/server/data
DATABASE_PATH=./backend/server/data/knowledge-graph.sqlite
UPLOAD_DIR=./backend/server/data/uploads
SQLITE_POOL_MAX_SIZE=4
SQLITE_POOL_MIN_IDLE=1
SQLITE_POOL_CONNECTION_TIMEOUT_MS=3000
SQLITE_BUSY_TIMEOUT_MS=5000
FRONTEND_ORIGIN=http://localhost:3010
```

约定从仓库根目录启动后端，不再使用 `./data` 作为默认路径。SQLite 文件和上传文件始终位于同一套 `backend/server/data/` 运行目录；如果从其他工作目录启动，请改为这套目录的绝对路径。

数据库连接池由 Spring Boot JDBC Starter 默认的 HikariCP 自动配置，默认最多 4 条连接、保留 1 条空闲连接，池耗尽后最多等待 3 秒。SQLite 连接统一启用 WAL、外键和 5 秒 `busy_timeout`：WAL 允许写事务期间读取已提交快照，但不会改变 SQLite 同一时刻只有一个写事务的约束；`SQLITE_POOL_CONNECTION_TIMEOUT_MS` 处理池耗尽，`SQLITE_BUSY_TIMEOUT_MS` 处理数据库写锁等待，两者职责不同。

启动时会自动创建目录，并执行项目内的幂等建表脚本：

```text
server/src/main/resources/db/schema.sql
```

当前脚本包含 `import_batches`、`source_documents` 表及索引，并使用中文 SQL 行注释说明表、字段和约束。SQLite 不支持 MySQL 风格的持久化表/字段 COMMENT，因此表结构说明以该 SQL 文件为事实源。MyBatis-Plus 负责业务表字段映射和 CRUD，数据库初始化仍由 SQLite 初始化器负责。

当前脚本同时包含 `knowledge_spaces`、`graph_nodes`、`graph_edges`、`evidences`、`review_actions`、`ai_extraction_runs`，所有来源资料、导入批次、抽取运行和后续图谱数据通过 `space_id` 隔离。生产数据库首次启动不会自动创建知识空间，旧版本数据库的兼容迁移仅处理已有历史记录。

当前导入接口只支持 UTF-8 Markdown/TXT。服务端会将原始文件保存到 `backend/server/data/uploads/<spaceId>/documents`，并保存解析文本和 SHA-256 内容指纹；同一空间内完全相同的字节内容不会重复落库。`kind` 表示 `markdown/txt` 文件格式，可选 `documentType` 表示 `general/prd` 业务语义。`GET /api/v1/spaces/{spaceId}/documents/{documentId}/content` 返回当前知识空间内的解析原文，前端默认按 Markdown 语法渲染，同时保留适用于 Markdown/TXT 的“原文”切换，不执行文档中的 HTML 或脚本。DOCX/PDF 解析仍在后续计划中。

## AI 模型配置

当前通过 LangChain4j 接入 OpenAI-compatible 模型，业务层只依赖 `AiExtractionClient`。默认示例使用自定义 Base URL 和模型名，真实调用默认关闭：

```properties
AI_ENABLED=false
AI_PROVIDER=openai-compatible
AI_BASE_URL=https://api.psydo.top/v1
AI_MODEL=gpt-5.4-mini
AI_API_KEY=your-server-side-key
AI_JSON_SCHEMA_ENABLED=false
AI_EMBEDDING_ENABLED=false
AI_EMBEDDING_MODEL=text-embedding-3-small
```

设置 `AI_ENABLED=true` 且提供 `AI_API_KEY` 后才会创建真实聊天客户端。`AI_JSON_SCHEMA_ENABLED` 只有在兼容端点确认支持原生 JSON Schema 后才能开启。Embedding 独立启用，不能使用聊天模型替代向量模型；启用前必须确认端点实际支持 `AI_EMBEDDING_MODEL`。

当前已经实现 PRD 结构化 DTO、证据反查、Markdown 章节解析、章节感知分片和手动抽取资源。每次调用会在 `ai_extraction_runs` 保存状态、模型与版本、完整结果或脱敏错误摘要；AI 调用不会随文档导入自动触发，也不会直接把候选结果写入正式图谱。

当前知识空间、来源资料、导入批次和图谱节点/关系/证据 Repository 均已迁移到 MyBatis-Plus Entity/Mapper；需要 Join 的证据查询 SQL 集中放在 `resources/mapper/*.xml`，不在 Repository 中使用 JdbcTemplate 手写业务查询。

后续会按 `documents`、`graph`、`reviews`、`health` 四个领域模块逐步补齐业务接口。

## 脚手架复用边界

复用统一响应、全局异常、错误码、TraceId、参数校验、Knife4j 和 Maven profile。不得复制原项目中的本地密钥、示例调试代码、旧包扫描路径或未提交改动。

## 安全边界

- AI API Key 只允许在 Java 后端环境变量或本地未提交配置中配置。
- `data/` 下的数据库和上传文件不提交到 Git。
- 后端不读取 EIS、个人知识库或现有工作文档目录。

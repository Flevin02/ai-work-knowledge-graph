# 后端服务

这里承载知识图谱维护助手的 Java 服务端能力，与前端工作台分离。

## 职责边界

- 文件接收、解析和内容指纹。
- SQLite 数据持久化。
- Gemini API 调用和结构化输出校验。
- 实体规范化、关系去重、证据保存和冲突检测。
- 图谱查询、关系审核和 Markdown/JSON 导出。
- API Key、数据库路径和上传目录等服务端配置。

## 当前状态

后端基于 `/Users/flevin/projects/firefly-boot/backend` 脚手架选择性复用，采用 Java 21、Spring Boot 3.2.11、`common + server` 两模块和 SQLite。复制后在当前仓库独立维护，不通过本机路径依赖原工程。

后端重构后将由 `server` 模块提供启动类和业务接口：

```bash
cd backend
kg_java_home=$(/usr/libexec/java_home -v 21)
JAVA_HOME="$kg_java_home" PATH="$kg_java_home/bin:$PATH" mvn test
JAVA_HOME="$kg_java_home" PATH="$kg_java_home/bin:$PATH" mvn -DskipTests install
cd server
JAVA_HOME="$kg_java_home" PATH="$kg_java_home/bin:$PATH" mvn spring-boot:run
```

默认地址：`http://localhost:4010`

统一前缀由 `server.servlet.context-path` 配置，默认值为 `/api`：

- `GET /api/health`
- `GET /api/v1/graph/summary`
- `GET /api/v1/documents`
- `POST /api/v1/documents/import`

Controller 内部不重复声明 `/api`，后续切换部署前缀只修改 `SERVER_CONTEXT_PATH`。

## SQLite 与来源资料目录

默认数据库和上传目录由以下环境变量控制：

```properties
DATABASE_PATH=./data/knowledge-graph.sqlite
UPLOAD_DIR=./data/uploads
FRONTEND_ORIGIN=http://localhost:3010
```

启动时会自动创建目录，并执行项目内的幂等建表脚本：

```text
server/src/main/resources/db/schema.sql
```

当前脚本包含 `import_batches`、`source_documents` 表及索引，并使用中文 SQL 行注释说明表、字段和约束。SQLite 不支持 MySQL 风格的持久化表/字段 COMMENT，因此表结构说明以该 SQL 文件为事实源。

当前导入接口只支持 UTF-8 Markdown/TXT。服务端会保存原始文件、解析文本和 SHA-256 内容指纹；完全相同的字节内容不会重复落库。DOCX/PDF 解析仍在后续计划中。

## AI 模型配置

Gemini 只是当前默认供应商，不是业务层硬编码依赖。通过以下配置选择供应商和模型：

```properties
AI_PROVIDER=gemini
AI_MODEL=gemini-2.5-flash
GEMINI_API_KEY=your-server-side-key
```

后续 AI 抽取能力通过 `AiExtractionClient` 接口封装，替换模型时不修改文档、图谱和审核领域逻辑。

后续会按 `documents`、`graph`、`reviews`、`health` 四个领域模块逐步补齐业务接口。

## 脚手架复用边界

复用统一响应、全局异常、错误码、TraceId、参数校验、Knife4j 和 Maven profile。不得复制原项目中的本地密钥、示例调试代码、旧包扫描路径或未提交改动。

## 安全边界

- Gemini API Key 只允许在 Java 后端环境变量或本地未提交配置中配置。
- `data/` 下的数据库和上传文件不提交到 Git。
- 后端不读取 EIS、个人知识库或现有工作文档目录。

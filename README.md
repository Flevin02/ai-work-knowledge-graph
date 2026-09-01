# AI 工作知识图谱维护助手

暂定产品名：知脉。

这是一个独立的参赛项目，与 EIS 后端、EIS 前端和个人/工作文档库隔离。项目目标是把散落的办公资料整理成有证据、可追溯、可持续更新的工作知识图谱。

## 项目目录

| 目录 | 用途 |
|---|---|
| `frontend/` | Next.js 页面入口、前端组件、类型和后端 API 客户端 |
| `backend/` | Java 后端、MySQL 事实库访问、文件解析和 AI 能力 |
| `docs/prd/` | 产品范围、架构边界和功能设计，不记录实施状态 |
| `docs/design/` | 实现级技术设计、数据模型、接口与交互说明 |
| `docs/decisions/` | 已确认设计决策、影响与回滚依据 |
| `docs/tests/` | 测试方案、评估数据和验证结论 |
| `fixture/` | 虚构、脱敏的演示资料 |
| `scripts/` | 导入、校验和演示辅助脚本 |

## 独立性边界

- 不依赖 EIS 源码、数据库、配置、运行环境或部署服务。
- 不直接读取 `/Users/flevin/Documents/docs` 中的个人或工作资料。
- 不把真实公司资料、密钥、Token、个人 ID 或绝对路径放入仓库。
- 只借鉴已有知识图谱维护中的通用规则：事实源优先、证据支持关系、人工确认和链接健康检查。
- 比赛演示使用 `fixture/` 下的虚构企业办公资料。

## 当前文档

- [产品需求文档](docs/prd/ai-work-knowledge-graph-maintainer-prd.md)
- [下一任务路线图](docs/roadmap.md)

## 本地启动

前端和后端分别在各自目录运行：

```bash
cd frontend
npm install
npm run dev
```

```bash
cd backend
kg_java_home=$(/usr/libexec/java_home -v 21)
JAVA_HOME="$kg_java_home" PATH="$kg_java_home/bin:$PATH" mvn test
```

正式启动后端时从仓库根目录执行：

```bash
cd ..
mvn -f backend/pom.xml -pl server -am spring-boot:run
```

默认前端地址为 `http://localhost:3010`，默认后端地址为 `http://localhost:4010`。正式后端运行数据统一保存到 `backend/server/data/`，前后端地址和 AI 配置通过 `frontend/.env.example` 和 `backend/.env.example` 参考配置。

## 技术栈

前端使用 Next.js、React、TypeScript 和 Cytoscape.js；后端采用 Java 21、Spring Boot 3.2.11、`common + server` 两模块、MySQL 8.0、MyBatis-Plus/MyBatis Mapper、LangChain4j、Jackson、Apache POI 和 PDFBox。后端代码在当前仓库独立维护，不依赖原 Firefly Boot 目录；固定资料规模的语义检索使用 MySQL 可重建向量事实和 Java 精确 COSINE，没有规模证据前不引入独立向量数据库或微服务集群。

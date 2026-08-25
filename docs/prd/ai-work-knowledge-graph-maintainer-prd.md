---
title: AI 工作知识图谱维护助手 PRD
version: v0.39
status: 开发中
created: 2026-08-17
updated: 2026-08-25
scope: 参赛项目 / 独立轻量应用
---

# 1. 文档说明

本文档定义“AI 工作知识图谱维护助手”（暂定名“知脉”）的第一版产品范围、交互流程、技术方案和验收标准。

本项目借鉴现有工作知识图谱维护体系中的通用规则，但不直接暴露 EIS 项目、真实工作文档、本机路径、任务编号或个人数据。参赛版本使用虚构的企业办公资料演示，产品应能够服务行政、人事、项目、销售和运营等非研发岗位。

# 2. 产品概述

## 2.1 一句话定位

自动理解散落的办公资料，建立有证据、可追溯、可持续更新的工作知识图谱。

## 2.2 要解决的问题

普通办公资料通常分散在会议纪要、活动方案、任务清单、合同、通知、周报和复盘文档中，用户可以搜索单个文件，却难以快速回答以下问题：

- 这个项目有哪些相关资料、任务和负责人？
- 某个任务是从哪次会议或哪份文档产生的？
- 最新文档是否改变了负责人、时间或状态？
- 哪些资料还没有归档到项目或部门？
- 两份文档是否存在互相矛盾的信息？
- 一条 AI 建议的关系是否有原文依据？

## 2.3 产品价值

产品不只提供问答，而是持续维护办公知识的结构和可信度：

1. 从文档中提取项目、人员、部门、任务、会议和资料等实体。
2. 建议实体之间的关系，并展示关系对应的证据片段。
3. 由用户确认后写入图谱，避免 AI 擅自篡改事实。
4. 新文档进入后增量更新关系、状态和时间线。
5. 自动检查孤立资料、失效链接、歧义关联、缺少字段和信息冲突。

## 2.4 文档关联主线调整（方向已确认，专项阶段 2 进行中）

当前实现已经完成实体、关系、证据和审核的技术链路，并已落地专项阶段 0、阶段 1 及阶段 2 的标签后端闭环和桌面 Web 标签审核小切片。专项产品与技术设计见 [`docs/prd/document-tag-and-association-rag-prd.md`](./document-tag-and-association-rag-prd.md)；真实标签模型、标签候选补充召回、文档关系图和 RAG/Embedding 仍未完成，不能按目标态描述为已有能力。

专项方案的核心边界是：文档关系图的节点直接来自真实 `source_documents`；默认先根据文档标题、摘要、正文、显式引用和关键词判断候选关联，标签只作为用户可选的筛选、补充召回和解释条件；AI 只生成候选标签和候选文档关系；关系必须通过服务端证据校验和人工审核。RAG/Embedding 用于后续增强内容候选召回和证据上下文，不是当前文档关联闭环的前置条件。已确认后续默认主线不再设置独立的“图谱类型”维度，也不把项目、部门、人员、任务等实体类型作为新文档分析的必选识别内容；默认业务节点收敛为文档，标签作为可选文档属性和检索索引。现有实体图谱数据、字段和历史抽取结果保留用于兼容、回溯和后续实验，不直接删除或改写。专项设计同时预留未来 Agent 编排接口，但当前默认仍是可解释的固定 Pipeline；Agent 不能绕过知识空间隔离、候选集合、证据校验和人工审核。

# 3. 目标用户与使用场景

## 3.1 目标用户

第一优先级：行政和人事文员、项目助理、运营人员。第二优先级：销售和客户成功人员、研发团队。产品界面不要求用户理解“实体、三元组、GraphRAG”等术语。

## 3.2 首个演示场景：公司年会筹备

使用一组脱敏的虚构文档：年会活动方案、两次筹备会议纪要、场地合同、供应商报价单、人员分工表、宣传通知和活动复盘。

系统应形成“年会项目—部门—人员—任务—会议—合同—通知—复盘”的关系图，并在导入第二次会议纪要后展示状态变化和冲突提醒。

# 4. 产品边界

## 4.1 MVP 必须包含

1. 支持导入 Markdown、TXT、DOCX 和 PDF 文本资料。
2. AI 提取实体、关系、状态、时间和证据片段。
3. 关系建议审核页：接受、拒绝、修改关系。
4. 可交互关系图谱：缩放、拖拽、搜索和邻居展开；节点类型筛选不再作为后续文档主线要求。
5. 节点详情页：摘要、来源文件、证据片段、关联关系和更新时间。
6. 图谱健康检查：孤立节点、失效来源、歧义链接、缺少关键字段和冲突信息。
7. 增量导入：新增资料后只处理新增或发生变化的内容。
8. 本地保存和导出：保存为 JSON 图谱和 Markdown 卡片，支持 Obsidian 兼容 Wiki 链接。
9. 演示数据一键导入，保证比赛现场可以稳定复现。

## 4.2 MVP 不包含

- 企业级权限、组织架构和多租户。
- 自动修改原始事实文档。
- 自动发送邮件、企业微信或钉钉消息。
- 生产级 OCR 和复杂扫描件版面还原。
- Neo4j、Qdrant、PostgreSQL 等重型基础设施。
- 自动发布公众号、朋友圈或其他外部平台。
- 直接接入真实公司文档和真实人员信息。

## 4.3 后续版本

- 多人协作和审核记录。
- 关系版本与时间旅行查询。
- 文档内容关联与标签增强：专项 PRD 已确认默认按文档内容关联、标签可选参与的方向；支持人工标签、AI 建议标签、按标签筛选和文档-文档候选关联。共同标签不能直接证明正式图谱关系，仍需内容证据或人工确认；RAG/Embedding 作为后续增强计划。当前尚未实现。
- 会议纪要自动转任务和提醒。
- 项目周报、交接材料和活动通知自动生成。
- 图谱驱动的活动海报和多渠道物料生成。
- 企业知识域模板和可配置关系类型。

# 5. 核心业务规则

## 5.1 事实源优先

原始资料是事实来源，图谱节点是对事实的索引和结构化视图，不复制完整正文。图谱摘要和关系与原文不一致时，必须回到来源文件确认，系统不得静默覆盖原文。

## 5.2 证据支持关系

AI 只能建议关系，且每条建议必须携带来源文件、来源段落或页码、证据原文片段、关系类型、AI 置信度和当前状态。不能仅凭文件名相似、关键词重叠或节点距离创建确定关系。

## 5.3 人工确认边界

- 新实体可以先进入“待确认”状态。
- 新关系默认以虚线显示。
- 用户确认后关系变为正式关系。
- 被拒绝的关系保留拒绝原因，避免重复推荐。
- 涉及负责人、金额、日期、合同状态和审批结论的变化，必须提示人工确认。

## 5.4 领域边界

项目、部门、客户、个人知识和其他知识域默认独立。只有当原文明确指向另一知识域，或用户主动确认时，才建立跨域关系。

## 5.5 增量更新

导入资料时使用内容指纹判断新文件、未变化文件、变化文件和失效来源。变化文件重新分析变更部分并保留历史版本；被删除或移动的来源标记为失效，不直接删除图谱节点。

# 6. 用户流程

## 6.1 首次建立图谱

```text
创建知识空间 → 导入办公资料 → 文本解析与内容指纹
→ AI 提取实体、关系和证据 → 用户审核关系建议
→ 写入图谱和 Markdown 卡片 → 查看关系图与健康检查
```

## 6.2 增量维护

```text
导入新会议纪要 → 识别新增、变化和冲突信息
→ 显示关系变化预览 → 用户确认
→ 更新图谱、卡片、反向链接和时间线
```

## 6.3 关系审核

审核卡片至少显示主体节点、关系类型、客体节点、证据来源、证据原文、置信度和风险提示，并提供接受、拒绝、修改操作。

# 7. 信息架构与页面

## 7.1 页面结构

### 工作台首页

知识空间列表、节点数量、关系数量、待审核数量、最近导入资料和健康检查摘要。

### 资料导入页

文件拖拽上传、文件类型和大小提示、解析进度、内容预览、重复文件和敏感信息提示。

### 关系审核页

待确认关系列表、证据片段高亮、低风险建议批量接受、单条修改关系类型或目标节点。

### 关系图谱页

现有实体图谱提供兼容性展示、搜索和邻居展开；后续文档关系图以真实来源文档为节点，按关系类型、状态、标签和时间筛选。节点类型不再作为用户主流程筛选维度，也不新增独立的图谱类型配置。

### 节点详情侧栏

文档摘要、标签、来源资料、更新时间、入边/出边和关系证据、历史变化、打开原文和导出卡片。旧实体图谱节点详情仅作为兼容视图保留。

### 健康检查页

孤立节点、失效来源、歧义链接、缺少负责人或截止时间、可能冲突的信息和待确认关系。

## 7.2 图谱交互要求

- 默认聚焦一个节点及其一到两层邻居，不默认展开全部节点。
- 节点颜色代表类型，边样式代表关系确认状态。
- 点击节点打开详情侧栏，点击边显示关系类型、来源和证据。
- 支持搜索节点并自动定位；不再要求通过节点类型隐藏任务、文档、人员等类别，文档主线改用标签、关系状态和文档类型筛选。
- 支持导出当前视图为 PNG 图片，用于比赛展示和汇报。

# 8. 数据模型

```ts
type NodeType =
  | 'project' | 'department' | 'person' | 'task'
  | 'document' | 'meeting' | 'risk' | 'decision';

type GraphNode = {
  id: string;
  type: NodeType;
  label: string;
  summary?: string;
  status?: 'active' | 'completed' | 'pending' | 'conflict' | 'orphan';
  sourceIds: string[];
  createdAt: string;
  updatedAt: string;
};

type GraphEdge = {
  id: string;
  source: string;
  target: string;
  type: string;
  status: 'suggested' | 'confirmed' | 'rejected' | 'stale';
  confidence?: number;
  evidence: Evidence[];
  createdAt: string;
  updatedAt: string;
};

type Evidence = {
  sourceDocumentId: string;
  quote: string;
  locator?: string;
  extractionMethod: 'ai' | 'rule' | 'user';
};

type SourceDocument = {
  id: string;
  name: string;
  path?: string;
  mimeType: string;
  contentHash: string;
  text: string;
  importedAt: string;
  updatedAt: string;
  status: 'active' | 'changed' | 'missing' | 'parse_failed';
};
```

# 9. AI 能力设计

## 9.1 AI 负责的内容

识别实体、规范化实体名称、识别候选关系、提取关系证据、识别任务状态/时间/负责人、发现可能冲突的信息并生成节点短摘要。

## 9.2 AI 不负责的内容

AI 不得直接修改原始文件，不得无证据创建确定关系，不得判断真实业务事实的最终正确性，也不得绕过人工确认修改负责人、金额、日期和审批状态。

## 9.3 结构化输出

AI 必须返回能够映射到 Java DTO 的结构化结果，禁止在业务层直接解析自由文本。兼容端点确认支持时优先使用原生 JSON Schema；端点不支持时使用 LangChain4j 的结构化 Prompt 和反序列化能力，并继续执行 Bean Validation、业务引用和证据反查：

```json
{
  "entities": [{
    "name": "2026年公司年会",
    "type": "project",
    "evidence": "本次会议讨论2026年公司年会筹备事项"
  }],
  "relations": [{
    "source": "行政部",
    "type": "负责",
    "target": "2026年公司年会",
    "evidence": "行政部负责统筹本次年会",
    "confidence": 0.94
  }],
  "conflicts": []
}
```

## 9.4 模型供应商策略

首版使用 LangChain4j 接入 OpenAI-compatible 协议，默认配置指向自定义 Base URL 和模型名。模型产品不是领域层固定依赖；后端通过统一的 `AiExtractionClient` 接口隔离框架和协议对象，领域层只依赖实体、关系、证据和冲突的结构化结果。

默认配置：

```properties
AI_ENABLED=false
AI_PROVIDER=openai-compatible
AI_BASE_URL=https://api.psydo.top/v1
AI_MODEL=gpt-5.4-mini
AI_API_KEY=仅服务端环境变量配置
AI_JSON_SCHEMA_ENABLED=false
AI_EMBEDDING_ENABLED=false
AI_EMBEDDING_MODEL=text-embedding-3-small
```

### 选择 LangChain4j 和 OpenAI-compatible 协议的原因

1. 项目的重点是学习模型调用、结构化输出、Embedding 和 RAG，而不是从零维护 HTTP 协议、流式解析和供应商响应对象。
2. LangChain4j 同时提供低层模型接口、AI Service、结构化输出、Embedding 和检索抽象，能够在 Java 后端内逐层学习而不引入独立 Python 服务。
3. OpenAI-compatible 协议允许通过 `provider + baseUrl + model + apiKey` 连接不同兼容端点，业务层不需要知道具体模型产品。
4. 真实聊天模型和 Embedding 模型独立配置，可以先验证结构化抽取，再确认端点支持的 Embedding 模型并启用 RAG。

### 自定义兼容端点的限制

- 需要网络访问和服务端 API Key，现场网络或账号状态异常会影响实时抽取。
- API 调用存在配额、速率和费用约束，不能把每次页面刷新都设计成一次模型调用。
- Base URL 可访问或接口返回 401 只说明路由和鉴权入口存在，不证明当前账号拥有指定模型权限。
- 不同 OpenAI-compatible 服务对 Chat Completions、Responses、原生 JSON Schema、推理参数和 Embedding API 的兼容程度可能不同，必须分别实测。
- 模型输出不是业务事实，仍可能漏识别、错合并实体或产生错误关系；必须保留证据并经过人工确认。
- 上传的办公资料会进入第三方模型服务，真实公司资料默认禁止直接上传，演示必须使用虚构或脱敏数据。
- 中文文档、表格、扫描件和复杂 PDF 的解析质量需要通过本地样本验证，不能仅凭模型宣传能力视为已验证。
- 供应商、模型名称和 API 响应格式可能变化，因此不能让 Controller、Repository 或图谱领域对象直接依赖 OpenAI-compatible 客户端字段。

### 替换边界

后续可以增加原生 OpenAI、Gemini、本地 Ollama 或其他协议实现，但替换范围应限制在 AI 适配层和配置层。以下模块不得感知具体模型供应商：

- 文档导入。
- 实体规范化。
- 关系去重。
- 证据审核。
- 图谱查询。
- 健康检查。

# 10. 技术方案

## 10.1 总体选型

| 层次 | 技术 | 选择原因 |
|---|---|---|
| 前端工作台 | Next.js + React + TypeScript | 承载导入、审核、图谱和健康检查界面 |
| 后端 API | Firefly Boot 脚手架 + Java 21 + Spring Boot 3.2.11 | 复用已验证的 Maven 父工程、统一响应、异常处理和 TraceId；后端只增加本项目业务能力 |
| UI | Tailwind CSS + shadcn/ui | 快速构建后台工作台，方便统一视觉风格 |
| 图谱 | Cytoscape.js | 支持节点布局、筛选、邻居展开和中等规模图谱交互 |
| 表单与校验 | React Hook Form + Zod | 表单状态和 AI 结构化输出共用校验模型 |
| AI | LangChain4j + OpenAI-compatible 模型（后端） | 支持自定义 Base URL 和模型名，以 `AiExtractionClient` 隔离协议，使用 Java DTO、Bean Validation 和证据反查校验结果 |
| 文本解析 | Java NIO、Apache POI、Apache PDFBox | 分别处理 Markdown/TXT、DOCX 和 PDF 文本 |
| 内容指纹 | Java MessageDigest SHA-256 | 判断文档是否新增或发生变化，避免重复调用模型 |
| 数据持久化 MVP | SQLite + MyBatis-Plus/MyBatis Mapper（后端） | 业务 CRUD 和查询统一使用 Mapper，数据库初始化保留轻量 DDL 执行器，避免 JPA/Hibernate 带来的额外复杂度 |
| 文件存储 MVP | 本地 uploads 目录 | 参赛演示无需对象存储，后续可替换为 S3/R2 |
| 导出 | Markdown + JSON + 图谱图片 | 兼容 Obsidian，便于备份、迁移和现场展示 |
| 测试 | JUnit 5 + Vitest + Playwright | 分别覆盖 Java 领域规则、前端规则和关键用户流程 |
| 部署 | Docker 或轻量云平台 | 保持单体部署，不引入微服务和复杂基础设施 |

## 10.2 架构分层

```text
浏览器
  ↓ HTTP API
Next.js 前端工作台
  ├─ 导入 / 审核 / 图谱 / 健康检查
  └─ 节点详情和证据展示
  ↓
Firefly Boot 后端
  ├─ common：统一响应 / 异常 / TraceId / Validation
  └─ server：本项目业务服务
       ├─ 文档导入服务
       ├─ AI 抽取服务（AiExtractionClient 适配层）
       ├─ 关系审核服务
       ├─ 图谱查询服务
       └─ Markdown/JSON 导出服务
  ↓
领域规则层
  ├─ 实体规范化  ├─ 关系去重  ├─ 证据检查
  ├─ 冲突检测    ├─ 失效来源标记  └─ 双向链接生成
  ↓
SQLite（仅 Java 后端访问）
  ├─ source_documents  ├─ graph_nodes  ├─ graph_edges
  ├─ evidences         ├─ review_actions └─ import_batches
```

文档标签与文档关联专项方案评审通过后，允许在 `server` 的应用服务外增加可选 `AgentOrchestrator`。它只负责动态工具选择、长流程暂停/恢复和步骤编排；标签、候选召回、关系判断、证据校验、审核状态机和持久化继续由独立领域 Service 负责。详细工具白名单、运行状态、不可绕过边界和框架替换范围见 [`docs/prd/document-tag-and-association-rag-prd.md` 第 14 节](./document-tag-and-association-rag-prd.md#14-agent-扩展架构)。

## 10.3 Firefly Boot 复用边界

后端脚手架来源为本机独立项目 `/Users/flevin/projects/firefly-boot/backend`。参赛项目复制必要的结构和通用代码后独立维护，运行时、构建时和部署时不得依赖原 Firefly Boot 工作目录。

### 复用内容

| 能力 | 复用方式 |
|---|---|
| Maven 父工程 | 复用 `common`、`server` 两模块结构和依赖管理方式 |
| 统一响应 | 复用 `ApiResponse<T>` 响应结构 |
| 异常处理 | 复用 `ErrorCode`、`BusinessException`、`TipsException` 和 `GlobalExceptionHandler` |
| 链路追踪 | 复用 `TraceContext`、`TraceIdFilter` 和过滤器注册配置 |
| 参数校验 | 复用 Spring Validation 和统一校验异常输出 |
| API 文档 | 复用 Knife4j / SpringDoc 基础配置，并修改为本项目包路径 |
| 环境配置 | 复用 local/prod profile 结构，所有密钥改为环境变量注入 |

### 不直接复制的内容

- Firefly Boot 中与当前项目无关的示例 Controller 和启动调试代码。
- 原项目中的产品名称、端口、包扫描路径和 API 文档扫描路径。
- 原项目本地配置中的任何密钥、Token、连接信息或其他敏感值。
- 当前项目暂不需要的线程池、AI 依赖或其他通用能力；确认使用场景后再引入。
- Firefly Boot 工作区中的未提交改动。

### 独立性要求

- 新项目使用独立 Git 仓库、Maven 坐标、包名、配置和数据库。
- 复制后的通用代码归当前项目维护，不通过本机绝对路径依赖原工程。
- 后端保持两个模块，不继续拆分第三个业务模块。
- `common` 只保存真正跨业务复用的技术能力；图谱、文档、审核和 AI 业务代码全部放在 `server`。

## 10.4 Obsidian 兼容

Obsidian 不是运行时依赖。系统内部使用数据库保存结构化数据，同时可导出原始资料索引、精简节点卡片、Vault 根路径格式的 Wiki 链接和 JSON 图谱快照，既能作为独立 Web 应用，也能保留与现有知识库工具的互操作能力。

# 11. 非功能要求

## 11.1 可解释性

每条已确认关系都必须能定位到来源文件；关系详情必须显示证据片段；AI 置信度不能替代事实依据。

## 11.2 性能目标

- 100 份以内的演示资料可在单机运行。
- 500 个节点、1500 条关系以内，图谱基本交互不明显卡顿。
- 相同文档重复导入不得重复调用 AI。
- 图谱初次加载目标不超过 3 秒，AI 分析时间单独显示进度。

## 11.3 安全与隐私

- API Key 只能放在服务端环境变量。
- 默认不上传真实公司资料到第三方模型，演示使用虚构数据。
- 上传文件路径、个人身份信息和密钥不得写入图谱卡片。
- 文件删除采用软删除或失效标记，避免误删事实源。
- 日志不得打印原始文档全文和敏感字段。

# 12. MVP 验收标准

## 12.1 资料导入

- [ ] 可以导入 Markdown、TXT、DOCX 和 PDF。
- [ ] 导入后能看到文件名、解析状态和文本预览。
- [ ] 相同内容重复导入时被识别为重复，不再次调用 AI。
- [ ] 解析失败时显示明确原因，不阻塞其他文件。

## 12.2 AI 抽取

- [ ] 能从演示资料中识别项目、部门、人员、任务、会议和文档。
- [ ] 每条关系建议包含关系类型、来源和证据片段。
- [ ] 无法找到证据时不得生成已确认关系。
- [ ] AI 输出不符合 Schema 时能安全失败并记录错误。

补充方向：进入文档标签与文档关联主线后，新的默认 AI 分析不再以项目、部门、人员、任务等图谱类型实体识别为必选目标；优先生成文档级摘要、候选标签和可追溯证据。现有实体抽取保留为兼容能力和可选实验，不作为文档关联闭环的前置条件。

## 12.3 关系审核

- [ ] 用户可以接受、拒绝和修改关系建议。
- [ ] 接受关系后图谱立即更新。
- [ ] 拒绝关系后不会在下一次相同导入中无限重复推荐。
- [ ] 审核操作记录操作者、时间和动作。

## 12.4 图谱展示

- [ ] 支持搜索、缩放、拖拽和节点详情。
- [ ] 现有实体图谱支持关系状态筛选；节点类型筛选不再作为新主线验收项。
- [ ] 文档关系图支持按标签、关系类型和关系状态筛选。
- [ ] 默认显示选中节点的一到两层邻居。
- [ ] 点击关系可以看到证据来源。
- [ ] 支持导出当前图谱视图图片。

## 12.5 健康检查

- [ ] 能识别孤立节点。
- [ ] 能识别失效来源。
- [ ] 能识别待确认关系。
- [ ] 能识别关键字段缺失。
- [ ] 能提示明显的日期、状态或负责人冲突。

## 12.6 增量维护

- [ ] 新增会议纪要后能展示新增节点和关系。
- [ ] 变更信息能在审核前以差异形式展示。
- [ ] 原关系不会被静默删除。
- [ ] 变更前后可以查看时间线。

# 13. 比赛演示脚本

1. 打开空白工作空间，展示“尚未建立图谱”。
2. 一键导入虚构的年会资料包。
3. 展示 AI 提取出的实体和待确认关系数量。
4. 打开一条关系，展示原文证据。
5. 接受关系并进入图谱视图。
6. 点击“年会项目”，查看一到两层关联节点。
7. 点击“健康检查”，展示孤立资料和冲突信息。
8. 导入第二次会议纪要。
9. 展示新增任务、状态变化和待确认关系。
10. 确认关系后导出 JSON、Markdown 和图谱图片。
11. 以一句话收束：系统让每份资料找到上下文，并让知识维护过程可追溯。

# 14. 开发计划

## 阶段一：图谱基础（1～2 天）

初始化 Next.js 前端，并基于 Firefly Boot 建立 `common + server` 两模块 Java 后端；完成 SQLite 数据模型、Markdown/TXT 导入和节点、关系、证据基础查询。

## 阶段二：AI 抽取与审核（2～3 天）

通过 LangChain4j 接入 OpenAI-compatible 模型，定义 Java DTO、结构化输出和证据校验，实现实体规范化、关系去重、证据保存和关系审核页面。

## 阶段三：图谱交互（1～2 天）

接入 Cytoscape.js，实现聚焦、展开、搜索、筛选、节点详情和证据侧栏。

## 阶段四：维护能力（2 天）

实现内容指纹、增量导入、冲突/孤立/失效来源/字段缺失检查，以及 Markdown/JSON 导出。

## 阶段五：比赛打磨（1～2 天）

准备虚构演示数据，调整图谱布局和视觉层级，增加演示引导和错误兜底，完成测试和部署。

# 15. 风险与应对

| 风险 | 影响 | 应对 |
|---|---|---|
| AI 关系错误 | 图谱可信度下降 | 强制保留证据、默认待确认、重要字段人工审核 |
| 全局图谱过于拥挤 | 评委无法理解 | 默认聚焦视图、类型筛选、邻居展开和健康视图 |
| PDF/DOCX 解析不稳定 | 导入失败 | MVP 先保证 Markdown/TXT，DOCX/PDF 提供明确失败提示和示例文件 |
| API 调用成本或网络不稳定 | 现场演示中断 | 准备本地演示快照、失败重试和离线示例模式 |
| 真实资料泄露 | 安全风险 | 全部使用虚构数据，密钥只放服务端环境变量，日志脱敏 |
| 过度依赖 Obsidian | 产品受众变窄 | Web 应用独立运行，Obsidian 仅作为导出格式 |
| 做成重型知识库平台 | 无法按期完成 | MVP 只做本地单体、SQLite 和有限文件类型 |

# 16. 成功标准

参赛 MVP 达到以下结果即可进入演示阶段：

1. 普通文员无需理解知识图谱术语即可完成资料导入和关系确认。
2. 资料导入后能形成可交互的项目关系图。
3. 每条正式关系都能回到证据原文。
4. 新资料进入后能展示新增、变化和冲突。
5. 系统能发现至少一种资料维护问题，而不是只做静态展示。
6. 全流程在虚构的公司年会资料包上稳定复现。

# 17. 尚待评审的决策

## 17.1 已确认决策

- [x] 项目使用独立仓库，不依赖 EIS、现有工作文档库或 Firefly Boot 运行目录。
- [x] 前端使用 Next.js、React、TypeScript 和 Cytoscape.js。
- [x] 后端基于 Firefly Boot 已提交基线选择性复用，保持 `common + server` 两模块。
- [x] 后端使用 Java 21 和 Spring Boot 3.2.11。
- [x] 数据持久化使用 SQLite 和 MyBatis-Plus/MyBatis Mapper，不使用浏览器 IndexedDB、JPA 或图数据库；数据库初始化和兼容迁移保留独立 DDL 执行器。
- [x] `/api` 由 `server.servlet.context-path` 统一配置，Controller 只声明业务路径。
- [x] AI 模型通过 `AiExtractionClient` 抽象；首个实现使用 OpenAI-compatible 协议、自定义 Base URL 和模型名，不是领域层固定产品依赖。
- [x] 首先完成 Markdown/TXT 导入，再在同一参赛版本中补齐 DOCX/PDF。
- [x] 首版不加入活动海报、周报、多用户权限等旁支功能，优先完善知识维护主线。
- [x] Obsidian 不是运行时依赖，只提供兼容的 Markdown/WikiLink 导出。

## 17.2 仍待确认

- [ ] 产品最终名称是否使用“知脉”。
- [ ] 比赛实际提交日期和部署环境。
- [ ] 比赛环境允许使用的外部模型 API 及网络条件。

# 18. 当前实施状态

更新时间：2026-08-25。

## 18.1 已完成

### 独立项目与文档

- 已建立独立 Git 仓库 `/Users/flevin/projects/ai-work-knowledge-graph`。
- 已建立 PRD、开发路线图、设计决策、演示资料和测试目录。
- 已明确项目与 EIS、工作知识库、个人知识库和 Firefly Boot 运行目录隔离。
- 已完成敏感配置扫描，当前项目未发现从 Firefly Boot 复制的明文密钥或 Token。
- 已新增根目录 `AGENTS.md`，固化新会话启动、分层编码、Knife4j、安全、验证、PRD 交接和小步提交策略。

### 前端原型

- 已升级并固定 Next.js 16.3.1，继续使用 React 18、TypeScript 和 Cytoscape.js；已通过生产构建验证当前 peer dependency 兼容范围。
- 已实现深色工作台布局、节点类型筛选、关键词搜索和图谱统计。
- 已确认后续默认主线不再展示独立“图谱类型”导航或筛选；图谱类型不再作为新增文档分析目标。左侧已改为“待处理 + 已确认标签统计”，只读取当前空间真实 `confirmed` 标签及有效文档数量，不显示 suggested/rejected 候选，也不伪造标签数据；现有类型字段及历史实体图谱仅保留兼容读取。
- 已在来源资料弹窗的“AI 输出”内拆分“文档标签 / 实体与关系（兼容）”两层：文档标签区支持服务端运行空态、处理中轮询、完成/失败恢复、逐字证据、单条/批量采纳拒绝、并发审核冲突回读和刷新后审核历史恢复；实体抽取兼容页仍可独立进入，但审核主界面只突出候选关联及其逐字证据，分片、候选实体、模型和 Prompt/Schema 元数据收进折叠技术详情。
- 前端不再注入硬编码演示图谱，图谱节点和关系只从当前知识空间的 Java 后端真实查询结果读取；首次进入没有知识空间时展示空态，必须先创建空间。
- 已实现节点点击、节点详情、来源资料和关系证据展示。
- 已优化 Cytoscape 默认布局：标签尺寸参与布局计算，节点间距、独立分组间距和缩放边界更适合中文标签展示。
- 已修复点击节点时因可见关系数组引用变化导致整张图重新布局的问题，点击现在只更新选中状态和节点详情。
- 已将关系审核收敛到 AI 抽取结果弹窗：抽取完成后以跨分片连续列表直接展示候选关系、置信度和对应原文证据，不再重复展示独立实体区和原文证据区；支持单条采纳/拒绝，也可点击待审核关系卡片形成整块选中状态后批量采纳或批量拒绝；全部处理后再关闭弹窗，不再保留独立“关系审核”导航页面。
- 已实现健康检查页面，可展示待审核关联、孤立节点和冲突节点。
- 已将“导入资料”入口接入 Java 后端 multipart API，当前允许选择 Markdown、TXT 和 PDF；PDF 卡片、侧栏统计与预览会明确标识文件类型和“服务端按页提取、不包含 OCR”的能力边界。
- 已在工作台加载后端持久化的真实来源资料，显示真实资料数量、最近文件和逐批新增/重复/失败结果。
- 已增加独立“来源资料”列表视图，展示文件类型、解析状态、文本预览、文件大小和 SHA-256 前缀。
- 已增加知识空间选择器、创建表单和软删除操作；首次启动不初始化默认知识空间，删除最后一个有效空间后允许回到无空间空态，空间删除仍会保留历史来源资料和图谱事实。无空间时左侧只保留状态说明，主工作区提供唯一创建入口；点击后以居中弹窗填写名称和用途说明，已有空间时的左侧新建图标也复用该弹窗。
- 后端不可用或导入失败时会明确显示错误，不再使用浏览器本地数据静默伪造导入成功。
- 已通过 `npm run typecheck` 和 `npm run build`。

### Java 后端脚手架

- 已基于 Firefly Boot 的已提交结构建立独立 `common + server` 两模块 Maven 工程。
- `common` 已提供统一响应 `ApiResponse<T>`、错误码、业务异常、提示异常、全局异常处理和 TraceId 过滤器。
- `server` 已提供 Spring Boot 启动类、健康服务和图谱摘要服务。
- 已通过 `server.servlet.context-path` 将统一前缀配置为 `/api`。
- 已配置 local/prod profile、Knife4j/SpringDoc 扫描路径和 AI 供应商环境变量。
- 已提供 RESTful 资源接口：`GET/POST /api/v1/spaces`、`DELETE /api/v1/spaces/{spaceId}`、`GET/POST /api/v1/spaces/{spaceId}/documents`、`GET /api/v1/spaces/{spaceId}/documents/{documentId}/content`、`DELETE /api/v1/spaces/{spaceId}/documents/{documentId}`、`GET/POST /api/v1/spaces/{spaceId}/documents/{documentId}/extractions`、`GET /api/v1/spaces/{spaceId}/documents/{documentId}/extractions/{extractionId}`、`GET /api/v1/spaces/{spaceId}/graph` 和 `GET /api/v1/spaces/{spaceId}/graph/summary`。
- 接口已返回统一响应结构和 `X-Trace-Id` 响应头。
- 已使用 Java 21 执行根 Reactor `mvn test`，测试通过。
- 已使用 Java 21 完成 Maven install，并验证两个接口返回 HTTP 200。
- 已增加 Knife4j/OpenAPI 基础信息配置，接口按“健康检查”和“知识图谱”分组展示。
- 已为 Controller 增加 `@Tag`、`@Operation`，并为统一响应和具体响应字段增加 `@Schema` 描述与示例。
- 未使用逐接口 `@ApiResponses`；响应结构由 SpringDoc 根据真实泛型返回类型自动推导。
- 已验证 OpenAPI 生成 `ApiResponseHealthStatusResponse`、`ApiResponseGraphSummaryResponse`、`HealthStatusResponse` 和 `GraphSummaryResponse` 模型。
- 已单独处理 `NoResourceFoundException`：缺失的 `favicon.ico` 仅记录 debug，其他缺失资源记录 warn，并统一返回 404。
- 已增加缺失 favicon 的 MockMvc 测试，验证 `error=true`、业务码 404 和“请求的资源不存在”消息。

### SQLite、知识空间与来源资料导入

- 已移除手工创建的 `SQLiteDataSource` Bean，改由 Spring Boot JDBC Starter 自动配置 HikariCP；默认最大连接数 4、最小空闲连接数 1、池获取超时 3 秒，并允许通过服务端环境变量覆盖。
- 已保留独立的本地存储目录初始化职责，启动时先创建数据库父目录和来源资料上传目录，再执行 SQLite 表结构初始化；每条物理连接统一启用 WAL、外键约束和 5 秒 `busy_timeout`。
- 已将幂等建表脚本放在 `backend/server/src/main/resources/db/schema.sql`，创建 `knowledge_spaces`、`source_documents`、`import_batches`、`graph_nodes`、`graph_edges`、`evidences`、`review_actions`、`ai_extraction_runs` 八张业务表。
- 建表 SQL 已为表、字段、外键和索引补充中文注释；SQLite 不支持持久化的 `COMMENT ON TABLE/COLUMN` 元数据，因此注释随项目 SQL 源码维护。
- 已建立知识空间和来源资料 Model、Repository、Service、Controller 分层，并通过 `space_id` 将资料、导入批次和后续图谱数据隔离到具体空间。
- 已建立图谱节点、关系和证据 Model/Repository/Service 查询链路，并将图谱摘要改为按空间读取 SQLite 实时统计；空空间返回零统计，不再返回固定脚手架文案。
- 已提供 `GET/POST /api/v1/spaces/{spaceId}/documents`、`GET /api/v1/spaces/{spaceId}/documents/{documentId}/content` 和 `DELETE /api/v1/spaces/{spaceId}/documents/{documentId}`；删除采用软删除，不物理删除事实来源。
- 已实现来源资料删除后的图谱同步失效：仅由该资料支撑的节点标记为 `stale`，多来源节点移除当前来源标识，关联失效节点或已无有效证据的关系标记为 `stale`；原始文件、证据和抽取运行继续保留以支持历史追溯。
- 已实现 Markdown/TXT 严格 UTF-8 解析和文本型 PDF 解析；PDFBox 按页提取可复制文本并写入稳定的“第 N 页”边界标记，空白页仍保留页码，损坏、受密码保护、零页和全页无可提取文本均返回明确逐文件失败。
- 已保留原始文件 UUID 落盘和原始字节 SHA-256 空间内去重语义；同一 PDF 在同一空间重复导入会复用已有记录，在不同知识空间可独立导入。
- 已为来源资料增加独立 `document_type` 业务类型，当前支持 `general` 和 `prd`；文件格式继续由 `kind=markdown/txt/pdf` 表示，避免文件格式和业务语义混用。
- 已实现导入批次统计和逐文件 `imported`、`duplicate`、`failed` 结果；单个解析失败不会阻断同批其他文件。
- 已限制服务端保存路径使用 UUID 文件名，并按 `uploads/<spaceId>/documents` 建立独立目录；不使用客户端文件名拼接本地路径，数据库写入失败时会清理本次孤儿文件。
- 已增加旧数据库 `space_id` 兼容迁移；仅当确有无空间归属的历史记录时，创建独立历史迁移空间承接它们。空间内内容指纹改为复合唯一约束。
- 已配置本地前端来源的 CORS 规则和 10 MB 单文件、50 MB 单批次上传上限，并覆盖 DELETE 预检。
- 已增加知识空间、文档导入、图谱查询和 CORS 集成测试，覆盖空间创建/软删除、空间隔离、表存在性、关系证据和资料导入边界。
- 已使用 Java 21 执行根 Reactor 全量测试；已使用真实临时 HTTP 服务导入 7 份虚构年会资料，并验证重复批次、SQLite 记录、独立上传目录、OpenAPI 路径和 CORS。当前测试总数和新增 PDF 证据见下方 AI/RAG 基础链路。

### AI/RAG 学习基础链路

- 已引入 LangChain4j 1.19.0，并通过 `AiExtractionClient` 隔离业务层与具体框架、模型供应商和协议实现。
- 已实现 OpenAI-compatible 模型配置，默认 Base URL 为 `https://api.psydo.top/v1`、模型名为 `gpt-5.5`；真实密钥仅从 `AI_API_KEY` 环境变量读取，不进入仓库。此前 `gpt-5.4-mini` 的真实调用证据只代表当次已验证模型，不自动证明新默认模型已联调。
- 已将真实 AI 调用默认设为关闭，只有显式配置 `AI_ENABLED=true` 且提供密钥时才创建真实客户端；模型、Base URL、Prompt 版本、Schema 版本、超时、重试和输出长度均已配置化。
- 已将聊天模型和 Embedding 模型拆成独立配置；Embedding 默认关闭，避免在尚未确认端点支持模型前把聊天模型错误用于向量化。
- 已定义分片摘要、实体、关系、证据和冲突的结构化抽取 DTO，实体类型固定包含项目、部门、人员、任务、文档、会议、风险、决策、需求和功能。
- 已实现结构化结果校验器，覆盖 Bean Validation、候选标识唯一性、关系实体引用、证据引用、来源资料/分片/章节一致性和逐字原文证据反查。
- 已增加 PRD Markdown 确定性章节解析器，能够保留 Front Matter 前言、标题层级、章节路径、代码围栏边界和原文偏移。
- 已增加章节感知分片器，短章节保持完整，长章节优先按换行边界切分并保留受控重叠和原文绝对偏移。
- 已增加 PRD 业务类型导入、非法文档类型、章节解析、长文分片、有效证据、幻觉引用和错误实体引用测试。
- 已使用 Java 21 执行根 Reactor `mvn test`，共 52 项测试通过；除分页、AI、PDF 和 SQLite 既有回归外，新增覆盖 SSE 多分片事件顺序、真实 Fake 增量转发、结构校验失败、失败运行恢复、OpenAPI 流媒体类型、OpenAI-compatible 流式完整文本解析和证据校验，单/多分片文档级全文摘要成功、摘要失败回退边界，以及上游可重试异常的安全提示和无部分结果持久化；真实流式模型、真实全文摘要质量、`gpt-5.5` 和真实 Embedding 调用未纳入默认自动测试。
- 已将 RESTful AI 抽取资源 `POST /api/v1/spaces/{spaceId}/documents/{documentId}/extractions` 改为 Spring MVC `text/event-stream`：稳定事件包含 `run_started`、`chunk_started`、`delta`、`chunk_completed`、`document_summary_started`、`document_summary_completed`、`completed` 和 `error`，每条事件携带当前运行和文档/分片定位，历史列表与详情查询接口保持不变。
- 已通过 `AiExtractionEventPublisher` 隔离抽取编排和 SSE 写出；客户端断线后停止向该连接写事件，但不会取消已经开始的后台抽取，完整结果仍可通过 `extractionRunId` 从服务端恢复。
- 已为 OpenAI-compatible 适配层增加 LangChain4j `StreamingChatModel`；`delta` 只转发供应商真实文本，不生成伪模型内容。模型完整响应解析为 DTO 后仍需通过结构、引用和逐字证据校验，完整结果成功写入 `ai_extraction_runs.result_json` 后才发送 `completed`，部分 JSON 或失败增量不会作为完整结果持久化。
- 已在确定性章节解析和分片完成后提前保存章节数、分片数，使失败运行也能恢复计划边界；流式改动没有新增 Embedding、向量检索、`topK`、阈值或上下文召回，当前仍是按确定性分片逐片直接抽取，不把传输层流式误称为 RAG 质量提升。
- 已增加 `ai_extraction_runs` 持久化运行记录，保存供应商、模型、分片抽取 Prompt/Schema 版本、文档级摘要 Prompt 版本、摘要状态/失败原因、章节/分片数量、文档级 AI 摘要、完整结果 JSON、脱敏错误摘要和完成时间；旧 SQLite 数据库会幂等补充摘要相关字段，并同步提供抽取历史列表和按 `extractionId` 查询详情的 RESTful 接口。
- 已将系统提示词迁移为结构清晰的 `prompts/prd-extraction-system.md`，并增加 classpath 资源测试，避免打包后出现 Prompt 资源缺失。
- 已接入 MyBatis-Plus 3.5.17；知识空间、来源资料、导入批次和图谱节点/关系/证据 Repository 已统一使用 Entity/Mapper，业务 Repository 不再使用 JdbcTemplate 手写查询。
- 需要明确 SQL 的图谱节点、关系和证据查询已迁移到 `backend/server/src/main/resources/mapper/*.xml`，Mapper 接口只保留方法签名和 Javadoc。
- 已增加来源资料原文预览接口 `GET /api/v1/spaces/{spaceId}/documents/{documentId}/content`，返回服务端解析后的 Markdown/TXT 原文或带页码边界的 PDF 提取文本，不暴露存储路径和原始 PDF 二进制。
- 前端来源资料卡片已增加“查看”按钮，Markdown/TXT 默认使用 Markdown 适配渲染；PDF 使用“文本预览/原文预览”文案并展示服务端提取文本，浏览器端不重复解析 PDF。来源资料弹窗现统一提供“渲染预览/原文预览/AI 输出”三个 Tab，标题和关闭按钮会随 Tab 联动，支持加载、错误、Escape、遮罩关闭和移动端长文滚动，渲染默认不执行原文 HTML。
- 前端已将 AI 提取状态收敛到对应资料卡片右上角，进入页面时直接使用服务端最近运行摘要初始化提取中、已完成和提取失败状态；顶部全局消息不再承担长耗时任务状态。
- 当前已加载资料中存在服务端 `processing` 抽取记录时，前端会在上一次请求完成 3 秒后静默刷新已加载页面；全部结束后不再调度，切换空间、搜索、导入、删除或组件卸载时会清理定时器并取消在途列表请求。
- 来源资料页复用服务端页码分页契约，前端已改为无限滚动：默认每次加载 12 条，触底按页追加并按文档 ID 去重；搜索、空间切换、导入和删除会回到第 1 页，加载更多失败保留已加载卡片并提供重试入口，`total/totalPages` 仅用于加载边界和完成提示。
- 来源资料页已增加按当前知识空间限定的文件名模糊搜索：后端通过参数绑定和 MyBatis-Plus 分页查询支持中文、大小写及 `%`、`_` 等特殊字符，前端空结果和清空条件均有明确状态；输入采用 300ms 防抖，停止输入后才请求一次列表接口，避免每个字符触发请求和顶部提示闪烁。
- 来源资料卡片底部仅保留“查看”和“AI 提取/重新提取/重试提取”两个操作；历史 AI 结果统一从“查看”弹窗的“AI 输出”Tab 恢复，不再在卡片中额外放置“查看结果”按钮。
- 后端来源资料列表已使用 MyBatis-Plus `PaginationInnerInterceptor` 生成 SQLite 分页和总数查询，默认每页 12 条、单页上限 100；`page` 和 `pageSize` 由 Jakarta Validation 校验。
- 当前页资料的最近抽取运行和最近成功结果标识使用一条窗口查询批量组装，未执行过抽取时显式返回 `not_started`，列表不返回完整 AI 结果 JSON，也不会对每份资料逐条查询抽取历史。
- 当前结构化输出版本保持 `extraction-v2`，分片抽取 Prompt 已升级为 `prd-extraction-v3`：继续要求每个分片返回只基于当前原文的非空短摘要，并新增固定实体关系白名单与主体/客体方向；分片完成后再通过独立的 `document-summary-v1` Prompt 发起一次 Reduce 调用，按章节顺序输入分片摘要并生成自然全文摘要，不再使用分号拼接。
- 已为全文摘要增加独立 Prompt 资源、OpenAI-compatible 领域客户端方法和 Fake 自动测试；汇总失败时 `ai_extraction_runs` 仍以 `completed` 保存已校验候选事实，`document_summary` 为空并记录 `failed` 状态与失败原因，来源资料列表继续回退导入原文 excerpt。
- 来源资料列表的 `excerpt` 已优先返回最近一次成功运行保存的文档摘要；未提取、旧版成功运行无摘要或从未成功时回退导入原文预览，后续重新提取失败不会覆盖历史成功摘要；前端在提取成功后重新读取当前页。
- AI 提取预览中的稳定关系类型编码已映射为中文业务文案，例如 `department_responsible_for_project` 展示为“部门负责项目”；未知编码统一展示为“关联”，不直接暴露技术标识。
- 前端已使用 `fetch` + `ReadableStream` 增量解析 SSE，并将实时流式进度、已校验分片、候选实体/关系/证据/冲突和运行元数据并入来源资料弹窗的“AI 输出”Tab；模型生成结构化 JSON 时，主区域只显示中文进度、已校验摘要和实体标签，原始 JSON 默认折叠在“技术详情”，避免把不可读的半截 JSON 作为用户主内容。
- AI 抽取完成后，候选实体、关系和逐字证据会幂等物化到当前知识空间；关系初始为 `suggested`。弹窗审核通过 `extractionId + chunkId + relationIndex` 提交到服务端，关系状态写入 `graph_edges`，动作写入 `review_actions`，刷新或重新打开结果时可恢复审核状态。
- 关闭流式处理窗口不会取消当前抽取；运行完成后继续复用现有历史详情接口恢复完整候选结果。失败状态会明确显示服务端稳定错误摘要，不会把连接中断或部分输出伪装成成功。
- AI 输出 Tab 没有运行记录时显示明确空态并引导开始抽取；已有成功运行时按 `latestCompletedExtractionId` 读取服务端完整结果，关闭已完成结果后再次打开会重新按运行标识恢复，而不是只依赖前端内存。最近抽取失败但存在历史成功结果时展示上次成功结果并保留失败提示；历史结果读取失败与 AI 抽取失败分别展示，不互相污染卡片状态。卡片按钮语义统一为“AI 提取”→“提取中…”→“重新提取”，失败为“重试提取”。
- 已使用 3020 前端、4020 后端、临时 SQLite 和纯虚构资料完成 hydration 后的自动化浏览器回归：覆盖 12 条/页的两页翻页、页码与空间切换取消旧页刷新、切回空间回到第一页、`processing` 自动收敛并停止轮询、无结果悬浮提示、最近失败时恢复历史成功结果、第二页导入后刷新第一页，以及删除第二页最后一条后回到有效页；本次无限滚动改动仅完成类型检查和生产构建，尚未执行新增触底交互的可见浏览器回归。
- 浏览器回归发现并修复资料变更提示被列表刷新提示覆盖的问题；当前导入批次摘要和删除结果会在对应空间的列表数据刷新完成后继续保留，普通首屏、翻页、空间切换和加载失败仍显示各自状态。
- 已在运行中的本地服务上完成 HTTP 联调：`GET /api/health`、知识空间查询、带 `Origin=http://localhost:3010` 的 CORS GET 和 multipart 预检均返回 200；临时知识空间上传虚构 Markdown PRD 后，`documentType=prd` 能正确持久化并在来源资料列表返回，临时空间随后已软删除清理。
- 已在 4030 隔离后端、临时 SQLite 和临时上传目录完成文本型 PDF HTTP 联调：multipart 导入返回 `kind=pdf`，原文接口返回页码标记与服务端提取文本，相同原始字节再次上传返回 `duplicate`，OpenAPI JSON 正常生成；临时运行数据已移入废纸篓。
- 已在 4010 服务使用上一版 `prd-extraction-v1` / `extraction-v1` 完成真实 RESTful 联调：首次请求疑似因 API 额度不足约 2 秒返回 503，并正确持久化失败运行；额度恢复后重试真实 `gpt-5.4-mini`，约 37 秒返回 HTTP 200，产生 6 个实体、5 条关系、6 条可反查逐字证据和 0 个冲突，历史列表与详情恢复成功，临时资料和空间随后已软删除。
- 来源资料卡片已收敛为“格式与状态、文档名称、完整摘要、文件大小与时间、操作按钮”五段：桌面端三列、中等宽度两列、移动端单列；摘要区吸收不同长度产生的留白，使元信息和底部按钮保持同一水平线。删除入口已改为右上角 icon-only，保留动态 `aria-label`、`title`、处理中禁用和原有二次确认。
- 来源资料页支持整卡片多选和紫色高亮；当前页选择后可执行批量提取和批量删除。批量删除调用 `POST /api/v1/spaces/{spaceId}/documents/deletion-batches`，在一个事务中完成多份软删除及图谱来源失效；批量提取调用 `POST /api/v1/spaces/{spaceId}/documents/extraction-batches`，后端有界线程池默认 2 并发、队列 12 条，每份资料仍独立创建运行、持久化成功/失败和候选审核上下文，前端继续复用当前页状态轮询。
- SHA-256 内容指纹已从资料卡片移除以保持列表简洁，并改在“渲染预览”Tab 的元信息区显示短值；悬浮可查看完整指纹，不在“原文预览”或“AI 输出”Tab 重复展示。
- 候选实体 `summary` 已收紧为只基于当前原文的 0～160 字符文本；中文、字母、数字、空格和标点均计入长度。Prompt 允许原文信息不足时返回空摘要，服务端 Bean Validation 只拒绝超过 160 的模型结果；候选图谱物化时空摘要回退为实体名称。
- Java 21 根 Reactor 全量 `mvn test` 共 51 项通过；新增覆盖批量删除软删除语义、批量提取后端线程池 2 并发和独立运行落库、批量 OpenAPI 契约、实体空摘要边界，以及单/多分片文档级全文摘要状态、SSE 阶段事件和摘要失败回退。前端 `npm run typecheck` 和 `npm run build` 通过。
- 已将 LangChain4j `RetriableException` 单独映射为“AI 上游服务暂时不可用，请稍后重试”，SSE 和失败运行只保存稳定安全提示，不泄露供应商错误正文，也不持久化部分结果；对应集成测试已纳入 52 项 Java 21 根 Reactor 测试。
- 来源资料名称搜索在请求完成后更新全局成功/空结果提示；批量选择状态增加读屏实时播报，资料卡片增加动态选择语义；移动端保留紧凑知识空间切换控件，不再隐藏整块空间入口。前端类型检查和生产构建通过，移动端触控与真实读屏仍待专项浏览器验收。
- 2026-08-23 已使用 3010 前端、4040 后端、临时 SQLite、15 份纯虚构资料和本地 OpenAI-compatible Fake 上游完成桌面 Web 真实 Chrome 自动化回归：覆盖 12 条首页触底追加至 15 条、整卡片鼠标/键盘多选、批量提取受理与状态收敛、批量删除确认弹窗取消、三 Tab、指纹完整值、AI 空态、失败/手工重试、历史结果与审核状态恢复。
- 同一隔离回归已验证真实 HTTP/SSE 分块交互：点击提取后 652ms 内可见首批模型增量，完整结果经结构和证据校验后恢复候选关系；上游在部分增量后断开时明确进入失败态，不把部分 JSON 当作成功；关闭弹窗不取消已开始任务，完成后可从服务端恢复。
- 桌面 Web 回归发现并修复批量 AI 提取受理提示被紧随的列表刷新覆盖问题；修复复用导入/删除已有的一次性提示保留机制，不改变批量接口、轮询和独立运行语义。
- 已为现有实体图谱兼容链路冻结 7 种关系白名单及固定方向：`PROJECT -> FEATURE`、`FEATURE -> REQUIREMENT`、`REQUIREMENT -> TASK/RISK`、`TASK -> PERSON`、`DEPARTMENT -> PROJECT`、`DECISION -> REQUIREMENT`。服务端同时校验关系编码、主体类型、客体类型和方向，未知或语义不匹配关系会使当前分片校验失败，不会被静默降级或写入待审核图谱；Prompt 版本同步升级为 `prd-extraction-v3`。
- 已使用固定双分片 Fake 资料验证跨分片规范化和候选物化：两个分片各输出带首尾空格差异的同一组项目/功能候选及同一关系，最终只保存 2 个 `spaceId + nodeType + normalizedKey` 节点、1 条待审核关系和 2 条可定位原文证据，同一来源资料标识在节点来源数组中不重复。Java 21 根 Reactor 全量 54 项测试通过；该结果不证明语义近似名称或别名能够自动合并。
- 已完成文档内容关联专项阶段 0：在 `fixture/document-association-v1/` 冻结 12 份自包含虚构资料、7 条覆盖五种文档关系的正例、5 组明确负例和 7 个候选召回用例，并覆盖表格、长文档、重复导入、版本变化和孤立资料；`annotations.json` 保存机器可读标签、关系、证据和召回答案。
- 已冻结 `document-association-v1` Prompt/Schema、`document-candidate-recall-v1` 候选召回策略、`document-association-policy-v1` 关联策略和阶段 2 的 `document-tag-v1` Prompt/Schema 边界；关联输出显式包含方向，关系选择使用“`updates` → `conflicts_with` → `supports` → `references` → `related_to`”最具体关系优先级。
- 阶段 0 静态验收已通过：JSON 可解析，文档/关系/用例标识唯一，正负答案无冲突，五种关系均有正例，长文档超过当前 1500 字符分片基线，所有标签与关系证据均可在对应文件逐字反查；`git diff --check` 通过。本轮只新增 fixture、设计契约和评估文档，没有新增产品表、接口、Service 或前端功能。
- 已完成专项阶段 1 第一小切片：新增 `document_association_runs`、`document_relations`、`document_relation_evidences` 和 `document_relation_reviews` 四张独立 SQLite 表及索引；新增 MyBatis-Plus Entity/Mapper/Repository、领域模型和 `DocumentAssociationPersistenceService`，保留现有实体图谱表不变。
- 持久化 Service 已验证有效知识空间和来源资料归属、内容指纹快照、五种关系白名单、关系方向、对称关系规范化、稳定 SHA-256 幂等键、运行归属、证据两端归属、逐字原文反查、证据角色和 suggested→confirmed/rejected 审核状态迁移；审核历史使用不可变插入记录。
- Java 21 根 Reactor 全量测试 65 项通过，其中包含文档关联持久化、Fake/SQLite Pipeline、MockMvc 运行/关系/审核和 OpenAPI 测试；覆盖新表启动、运行/关系/证据/审核落库、空间隔离、自关联、非法方向、对称关系重复、错误证据、空召回、审核状态恢复和 API 契约。
- 已完成专项阶段 1 第二小切片：新增 `DocumentCandidateRecallService`、候选领域模型和确定性无 Embedding 召回实现；严格限定同一知识空间的有效来源资料并排除主体文档，按显式引用、标题、章节标题、摘要和正文关键词融合去重，固定 `document-candidate-recall-v1` 与 TopK=8，返回命中通道、有限关键词、规则分数和稳定排名。
- 固定 `document-association-eval-v1` 的 7 个召回用例已通过 Java 21 SQLite 集成测试：期望候选均进入前 8，孤立打印机资料返回空列表，冻结硬负例未进入对应前 8，TopK 超过 8 被明确拒绝；该测试只证明本地虚构资料上的确定性召回，不证明真实资料或语义召回质量。
- 已完成专项阶段 1 第三/四小切片：新增供应商无关 `DocumentAssociationClient`、Fake 关系判断 Pipeline、创建/恢复关联运行 API、关系查询 API 和批量审核 API。服务端限定模型只能逐一判断最多 8 份候选；`none` 不持久化；非 `none` 必须通过关系白名单、方向、候选集合、`chunkId`/`sectionPath` 和 quote 逐字反查，随后以 `suggested` 幂等保存关系及证据；审核只接收服务端关系标识和 `accept/reject` 动作。
- 新增 Fake/SQLite/MockMvc 集成测试，覆盖有效关系、无效证据不入审核、空召回不调用模型、重复运行幂等、审核状态恢复、HTTP 路径和 OpenAPI 具体响应模型；该小切片当时通过 Java 21 根 Reactor 65 项测试，只证明最小固定 Pipeline 和本地边界，完整固定资料指标随后由下一项端到端评估补齐。
- 已完成专项阶段 1 固定资料端到端质量评估：12 份资料均从关系两端执行真实候选召回、有限分片上下文、服务端证据校验和 SQLite 持久化，Fake 仅按冻结标注返回关系类型、方向和精确 quote。最终 7 条正例全部落库，Recall@8、关系类型准确率、方向准确率、证据有效率和非 none Precision 均为 1.0000；无依据、重复、自关联、跨空间关系和硬负例召回均为 0。Precision@8 微平均为 0.1707，作为后续标签/混合召回降低噪声的对照基线。
- 评估首次从关系两端运行时暴露有向关系重复建议率 0.3636；已按冻结幂等契约从关系键中移除“相对于本次运行”的 `direction`，保留最终主体/客体、关系类型、内容指纹和策略版本，并补充反向运行回归。Java 21 根 Reactor 67 项测试全部通过，评估报告位于 `docs/tests/document-association-evaluation-report-v1.md`。
- 已完成专项阶段 2 第一小切片：新增 `tags`、`document_tags` 和 `document_tag_evidences` 三张 SQLite 表及索引，使用 MyBatis-Plus Entity/Mapper 和聚合 Repository 隔离 ORM；`DocumentTagPersistenceService` 负责空间/来源资料/内容指纹校验、标签轻量规范化、AI `suggested` 与用户 `confirmed` 初始状态、模型版本快照和逐字证据原子保存。
- 标签字典按 `spaceId + normalizedKey` 复用；AI 文档标签按照第 17.1 节冻结的 `spaceId + sourceDocumentId + contentHash + normalizedTagKey + promptVersion + schemaVersion` 生成 SHA-256 稳定键，同版本重复运行复用旧候选，Prompt 版本变化保留新候选。标签专项 4 项和隔离后的 Java 21 根 Reactor 71 项测试通过；该切片当时尚未实现标签运行、审核历史/API、前端标签导航和浏览器。
- 已完成专项阶段 2 第二小切片：新增独立 `document_tagging_runs`、MyBatis-Plus 运行映射与 `DocumentTaggingClient` 供应商隔离边界；`POST /documents/{documentId}/tagging-runs` 同步执行 Fake 标签 Pipeline，`GET /documents/{documentId}/tagging-runs/{runId}` 按空间、文档和运行标识恢复状态、摘要和本次新保存候选。
- 标签输入固定经过“来源原文 → 确定性章节 → 章节感知分片 → 最多 32 分片且合计不超过 24,000 字符的安全上下文 → document-tag-v1 输出”；服务端依次执行 DTO/Bean 结构校验、候选/证据局部标识和引用校验、当前文档分片/章节/quote 逐字反查，再由服务端生成数据库标识并以 `suggested` 批量原子物化。相同内容和 Prompt/Schema 重复运行保留新运行记录但不重复增加标签或证据。
- 新增标签运行 Fake/SQLite/MockMvc 与 OpenAPI 测试，覆盖成功运行和 GET 恢复、未知证据引用、无效 quote 不落候选、重复运行幂等、12 章节长文档上下文和旧 SQLite 缺列兼容迁移；Java 21 根 Reactor 全量 80 项测试通过。该结果不代表固定 `expectedTags` 全量 Precision/Recall、真实标签模型、标签审核、前端或生产联调。
- 已完成专项阶段 2 第三小切片：新增 `document_tag_reviews` 不可变审核历史表及 MyBatis-Plus/MapStruct 分层；`GET /documents/{documentId}/tags` 批量恢复标签定义、逐字证据、状态和审核历史，`POST /documents/{documentId}/tag-review-batches` 只按服务端文档标签关系标识执行 `suggested → confirmed/rejected`，`GET /spaces/{spaceId}/tags` 只聚合已确认标签及有效来源资料数量。
- 标签批量审核在一个事务中先验证同批重复、空间/文档归属和当前状态，再原子更新状态并插入历史；跨文档标识或重复审核分别返回 404/409，不留下半批结果。新增 3 项 SQLite/MockMvc/OpenAPI 集成测试后，Java 21 根 Reactor 全量 83 项测试通过；该结果不代表前端、浏览器、真实模型、固定资料标签质量或生产联调。
- 已完成专项阶段 2 第四小切片：新增 `GET /documents/{documentId}/tagging-runs/latest`，桌面 Web 不依赖浏览器本地业务数据即可恢复最近标签运行；标签概览消费运行、标签明细、审核和空间统计 API。隔离 Chrome 使用临时 SQLite 和纯虚构资料验证了真实空态、默认客户端未启用失败态、处理中刷新恢复与自动收敛、完成态、逐字证据、单条/批量审核、重复审核冲突、左侧仅 confirmed 统计及整页刷新恢复。成功/处理中运行由隔离 SQLite 测试数据构造，不代表真实标签模型调用。
- 已完成专项阶段 2 confirmed 标签候选补充及三轮质量实验：默认关闭时继续使用 `document-candidate-recall-v1`；显式开启时，`document-candidate-recall-v3` 只补充内容通道未命中且至少共享两个 confirmed 标签的候选。独立 `document-association-tag-threshold-eval-v2` 正负例对照将 Recall@8 从 0.8750 提升至 1.0000、Precision@8 从 0.1707 提升至 0.1860，同时记录 1 个跨项目硬负例；因此保留数量阈值作为最低候选门槛，不直接建立关系或跳过后续审核。固定资料静态校验和 Java 21 根 Reactor 全量 85 项测试通过。
- 已引入 MapStruct 1.6.3 和 Lombok 绑定注解处理配置，将 AI 抽取运行、文档关联、来源资料、知识空间及图谱 Repository 中重复的 ORM Entity/领域模型转换集中为编译期映射器，并将知识空间、来源资料、文档关联和图谱的接口响应转换从 Service 实现中独立出来；查询、排序、事务、状态迁移和外部 API 字段保持不变。
- 持久化映射统一复用 `PersistenceMappingSupport` 处理 ISO-8601 时间、文档类型兼容回退、可空数值和图谱来源标识 JSON，所有映射器使用 `unmappedTargetPolicy=ERROR` 在编译期拒绝遗漏目标字段；新增 4 项集中映射边界测试，Java 21 根 Reactor 全量 75 项测试通过。

## 18.2 当前验证边界

- 前端图谱、来源资料和关系审核均已具备 Java 后端读取/写入链路；首次无知识空间或没有真实图谱数据时展示真实空态，不再保留虚构演示图谱作为运行时兜底。
- 新导入资料已经写入 SQLite 和服务端上传目录，但尚未触发 AI 抽取，也不会自动生成图谱节点、关系或证据。
- 图谱节点、关系和证据的持久化查询链路已完成，但当前真实空间没有 AI 写入的正式图谱数据；手工关系写入仅在集成测试中验证。
- 当前重复识别基于原始文件字节的 SHA-256 完全一致；尚未实现同一文档不同版本的内容差异预览。
- 当前 PDF 能力只面向可复制文本：没有恢复图片、表格结构、复杂多栏阅读顺序、字体语义或扫描件文字；扫描 PDF 会明确提示当前未启用 OCR，DOCX 仍留在后续主线。
- `AiExtractionClient`、OpenAI-compatible 适配层、结构化 DTO、校验器和候选物化已接通手动抽取接口；固定实体关系白名单、主体/客体方向以及精确规范化键的跨分片合并已通过 Fake/SQLite 自动测试，但 `prd-extraction-v3` 尚未执行真实模型调用，语义近似名称、别名和跨类型同名实体不会自动合并。
- 已验证当前账号和端点能够调用 `gpt-5.4-mini` 并返回 Prompt 约束的结构化输出；仍未验证原生 JSON Schema 模式、Responses API 结构化抽取或 `text-embedding-3-small`。
- 当前 `prd-extraction-v3` / `extraction-v2` 的分片摘要、实体 0～160 字符摘要和关系白名单已通过 Fake AI、结构校验和 SQLite/MockMvc 集成测试，但尚未对真实 `gpt-5.5` 验证实体长度遵循、摘要质量、关系白名单遵循和不同分片的重复表达；实体摘要是生成性展示内容，不能替代逐字证据和人工审核。
- `AI_JSON_SCHEMA_ENABLED` 默认关闭；当前可使用 LangChain4j 的 Prompt 约束结构化输出，但自定义端点是否支持原生 JSON Schema 需要真实请求后单独验证。
- Embedding 真实客户端默认关闭，尚未生成、缓存或检索任何真实向量；`document_sections` 和 `document_chunks` 也尚未持久化。当前只持久化抽取运行元数据和完整结果 JSON，尚未把章节、分片、候选实体、关系和证据拆成正式领域表记录。
- PRD Markdown 章节解析和分片已接入手动 AI 抽取动作；PDF 当前会因没有 Markdown 标题而作为根章节继续按长度分片，尚未设计 PDF 专用章节/表格结构，也未验证 PDF 经当前 AI 抽取后的语义质量和证据定位体验。
- 当前分片数量不是固定 6 片：章节解析后，短章节保持完整，超长章节按 `AI_RAG_MAX_CHUNK_CHARS`（默认 1500）和 `AI_RAG_OVERLAP_CHARS`（默认 150）切分；这些是待评估的实验基线，不代表最佳分片策略。
- 浏览器回归使用临时安装且未写入项目依赖的 Playwright Core 驱动本机无头 Chrome，并使用隔离端口、临时 SQLite、虚构文件和 Fake 抽取运行记录；它证明了当前 hydration 后的交互状态，不等于可见浏览器人工验收、真实模型并发运行或生产网络验证。
- 当前自动测试已断言抽取资源的 OpenAPI `200` 响应包含 `text/event-stream`；`SourceDocumentResponse.documentType` 的枚举值仍重复展示两组 `general/prd`，属于注解显式值和枚举推导叠加的文档问题，后续应去除重复声明并补充对应断言。
- 数据库启动初始化和旧库兼容迁移仍使用 JDBC/DDL 执行器，这是数据库结构职责；业务数据的 CRUD、条件查询、统计和 Join 均通过 MyBatis-Plus/MyBatis Mapper 完成。
- MapStruct 本轮只统一 Java 进程内的 ORM Entity、领域 record 和响应 DTO 转换，没有修改数据库表、SQL、REST 路径、JSON 字段或业务状态机；75 项自动测试证明当前映射编译、核心空值/时间/JSON 边界和既有后端回归通过，不等于真实模型、浏览器、生产数据库或部署联调。
- 当前 SQLite 有界连接池、WAL、外键、`busy_timeout`、池耗尽和单写锁等待已通过本地自动测试；测试证明长耗时 Fake 模型调用不持有数据库连接，也证明第二写者会在忙等待后失败，不代表真实模型并发、生产负载或多实例共享 SQLite 已完成验证，更不代表 SQLite 获得多写并发能力。
- 当前 AI 抽取已使用 SSE 返回真实运行事件和模型增量，但尚未对真实 `gpt-5.4-mini` 流式接口、首事件 5 秒目标、反向代理缓冲和真实网络断线完成联调；MockMvc 事件顺序和前端构建不能替代真实 HTTP 分块时序或生产代理验证。
- 流式进度、结构化结果和历史结果已经合并到来源资料弹窗的 AI Tab，并有无运行空态、历史结果读取失败、最近失败回退历史成功和关闭后重新读取逻辑；桌面 Web 已完成隔离 Chrome 交互回归。根据当前产品优先级，移动端布局、触控和读屏验收顺延到 Web 主线稳定后的专项任务，不阻塞当前阶段。
- 实体关系兼容页的展示简化已通过前端 TypeScript、生产构建和本机可见 Chrome 验证：最近失败且存在历史成功结果时使用顶部全宽窄提示并可展开失败原因，结果面板与提示上下排列且无横向溢出；审核主区域只展示连续候选关系列表及对应证据，候选实体和运行元数据仅在折叠技术详情中显示。浏览器验证只执行空间切换、查看和展开等只读操作，没有触发重新提取、审核或删除，也不证明真实模型或生产环境质量。
- 当前仍没有取消、断线重连、事件续传或部分输出持久化能力；关闭弹窗只停止界面展示，不会伪装为取消或中断后台抽取。
- `AI_MAX_RETRIES` 当前只应用于同步聊天模型；LangChain4j 流式客户端未提供同等构建参数，而且收到部分增量后自动重试可能产生重复文本，因此本版没有静默为流式请求追加重试策略。
- 当前来源资料旧版分页、空间切换、导入回第一页、页末删除、历史结果恢复和 `processing` 自动收敛已有隔离浏览器证据；本轮进一步验证桌面 Web 无限滚动从 12 条追加到 15 条且无重复卡片。加载更多失败重试仍缺少可控故障注入证据，临时回归脚本也未纳入仓库级持续回归。
- 本次来源资料名称搜索已通过 Java 21 根 Reactor 全量 43 项测试、前端 `npm run typecheck` 和 `npm run build`；后端测试覆盖当前空间隔离、中文、大小写、问号、`%`、`_`、空结果、分页边界和 OpenAPI 参数。尚未执行可见浏览器下的视觉验收或真实网络面板请求次数统计。
- 来源资料卡片的桌面三列布局、整卡片鼠标/键盘多选、批量按钮、右上角 icon-only 删除入口和渲染预览 Tab 指纹完整值已通过桌面 Chrome 回归；浏览器内的批量删除本轮只验证确认弹窗并取消，没有删除隔离数据。移动端和读屏回归已明确顺延。
- 批量提取接口仅验证了后端 Fake 模型下的 2 并发任务受理、独立运行持久化和队列拒绝响应结构；尚未验证真实模型并发限额、线程池满载、真实 SQLite 写锁等待、页面离开后状态恢复或生产部署中的任务观测。批量删除已验证事务内成功路径，尚未对中途异常的完整回滚做故障注入测试。
- 当前 `document_summary` 已改为“分片摘要 Map → 一次模型 Reduce 汇总”的自然全文摘要；单分片和多分片均执行独立汇总阶段，失败时不影响已校验候选事实落库并回退导入原文 excerpt。Fake/SQLite/MockMvc 已验证状态、事件和失败原因，真实 `gpt-5.4-mini` 的摘要质量、长度遵循度和跨章节自然度仍未验证。
- 已确认文档关联主线的产品边界：默认业务节点收敛为真实来源文档，默认先按文档内容关联；标签仅作为用户可选的筛选、补充候选和解释条件。独立图谱类型不再是后续用户流程或新增 AI 识别的必选维度。文档关系持久化和后端 API、标签字典、文档标签、标签证据、独立 Fake 标签运行、标签查询、不可变审核历史、批量审核 API、桌面 Web 标签运行/导航统计/审核联调、confirmed 标签候选补充及文档关系图最小切片均已完成；真实标签模型、手工标签编辑、文档详情跳转、关系证据定位、筛选、邻居高亮和移动端仍待专项后续阶段。
- 当前标签运行只使用测试注入的 Fake `DocumentTaggingClient`；默认生产上下文没有真实实现时会以 `tag_extraction_failed` 结束并可恢复。当前上下文上限为 32 分片、24,000 字符，超限明确记录 `chunk_failed`，不会静默只分析部分原文；该参数尚未通过真实模型 Token、延迟或长文质量评估。
- 文档关联阶段 1 已完成运行、关系、证据、审核的本地持久化基础、无 Embedding 候选召回、Fake 关系判断、逐字证据校验、后端审核 API 和固定资料完整指标评估。文档关系图最小切片已新增独立 `/v1/spaces/{spaceId}/document-graph` 查询和桌面切换入口，并以隔离 SQLite/Chrome 验证真实来源文档节点、confirmed 边过滤和空态；详情跳转、关系证据定位、筛选、邻居高亮、真实模型关系质量和生产验证仍未完成，Precision@8 0.1707 也表明候选上下文噪声仍需后续对照优化。
- 已完成固定资料开关对照：使用 document-association-eval-v1 的 12 份资料和冻结 expectedTags 作为人工 confirmed 输入，只改变 includeConfirmedTags=false/true，TopK 固定为 8。两条路径 Recall@8 均为 1.0000；开启标签后候选总数由 41 增至 48，Precision@8 由 0.1707 降至 0.1458，固定硬负例未新增。结论是当前标签通道能补候选但会引入未标注候选噪声，不能称为质量提升；详细结果见 docs/tests/document-association-tag-augmentation-evaluation-v1.md。
- 已完成阶段 2 的 confirmed 标签补充候选最小切片：`POST /documents/{documentId}/association-runs?includeConfirmedTags=false` 默认保持无标签内容召回；用户显式传 `true` 后，服务端一次读取当前空间有效文档的 `confirmed` 标签，以共享标签补充候选并记录 `tagCandidateCount`、`keywordCandidateCount` 和 `confirmed_tag_match` 生成方式。共同标签只影响候选召回，不直接形成关系或跳过证据校验/人工审核；Java 21 根 Reactor 全量 84 项、前端 typecheck/build 通过。
- 已完成阶段 2 标签候选降噪单变量实验：新增 `document-candidate-recall-v2` 版本，confirmed 标签只补充所有内容通道均未命中的候选，并排在内容候选之后；固定资料回归证明默认内容候选顺序恢复，开启标签后的候选总数仍为 48、Precision@8 仍为 0.1458，Recall@8 仍为 1.0000，固定硬负例仍为 0。该策略未带来质量提升，下一实验改评估共同标签数量分层阈值；报告见 docs/tests/document-association-tag-augmentation-evaluation-v1.md。
- 已完成阶段 2 共同标签数量分层阈值实验：新增 `document-candidate-recall-v3`，标签-only 候选需共享至少 2 个 confirmed 标签。固定资料的 7 个新增候选均只共享 1 个标签，因此全部被过滤；开启标签后候选总数与 Precision@8 均回到无标签基线（41、0.1707），Recall@8 保持 1.0000。该结果只证明单标签噪声得到抑制，未证明标签能补充正例召回；报告见 docs/tests/document-association-tag-threshold-evaluation-v1.md。
- 已完成阶段 2 双共同标签正负例对照：保留冻结的 `document-association-eval-v1`，新增独立 `document-association-tag-threshold-eval-v2` confirmed 标签补充标注、同项目正例和跨项目明确负例。开启 `document-candidate-recall-v3` 后 Recall@8 由 0.8750 提升至 1.0000、Precision@8 由 0.1707 提升至 0.1860、候选总数 41→43；2 个标签候选中包含 1 个跨项目硬负例。结论是保留双共同标签作为最低候选门槛，但不能据此确认关系，仍需关系判断、逐字证据校验和人工审核；报告见 docs/tests/document-association-tag-threshold-evaluation-v2.md。
- 2026-08-21 已完成目标态核验：当前浏览器中的真实页面仍是实体/关系混合图，节点点击只更新右侧详情，来源资料名称没有详情页跳转；标签区是空态；没有问答、引用或 Agent 运行入口；Cytoscape 没有悬浮/键盘邻居高亮。2026-08-25 已补文档关系图最小切片：独立查询真实来源文档节点和 confirmed 边，并可在桌面 Web 的实体兼容图谱/文档关系图之间切换；文档详情页、标签叠加、固定 RAG 有据问答和可选 Agent 仍保持未实现状态。
- 本轮 PDF 前端契约已经通过 TypeScript 检查和生产构建，但当前会话缺少浏览器控制运行工具，未执行 PDF 文件选择、卡片标签和预览弹窗的新增浏览器回归；既有 Markdown/TXT 浏览器证据不能替代该项验收。
- 本地 HTTP 与浏览器验证只使用 `fixture/annual-party/` 和临时生成的纯虚构资料；已进行一次真实模型结构化抽取，但本轮浏览器回归使用 Fake 运行状态，未使用真实公司资料，也未进行生产部署或真实模型浏览器端到端验证。
- 历史真实结果虽通过结构、引用和逐字证据校验，但模型曾把“项目到人员”的关系错误归类为 `project_contains_feature`；当前 `prd-extraction-v3` 已增加服务端类型方向校验以拒绝该类结果，但尚未使用真实模型复验，人工审核边界继续保留。
- 当前浏览器证据来自本机无头 Chrome 自动化；本次 confirmed 标签补充入口只完成前端契约、构建和后端服务验证。当前会话未提供可调用的浏览器控制工具，因此未执行该入口的浏览器点击回归；仍未进行可见窗口下的人工视觉验收、移动端触控回归和辅助技术读屏验收。
- 本轮无知识空间空态、主工作区创建入口与创建弹窗已通过前端类型检查和生产构建；尚未执行点击遮罩、Escape 关闭、创建成功切换空间和移动端布局的可见浏览器回归。
- 本机默认 Maven 运行时可能使用 Java 25；本项目必须显式使用 Java 21，避免 Lombok 注解处理失败。
- OpenAPI JSON 已在本地临时端口验证路径、标签和响应模型，但尚未进行独立部署后的 Knife4j 页面人工验收。

# 19. 下一步代办与新会话入口

新会话开始时，先阅读本节、`docs/roadmap.md`、[`docs/prd/document-tag-and-association-rag-prd.md`](./document-tag-and-association-rag-prd.md)、[`docs/tests/document-association-evaluation-report-v1.md`](../tests/document-association-evaluation-report-v1.md)、[`docs/tests/document-association-tag-augmentation-evaluation-v1.md`](../tests/document-association-tag-augmentation-evaluation-v1.md)、[`docs/tests/document-association-tag-threshold-evaluation-v1.md`](../tests/document-association-tag-threshold-evaluation-v1.md)、[`docs/tests/document-association-tag-threshold-evaluation-v2.md`](../tests/document-association-tag-threshold-evaluation-v2.md) 和 `document-tag-v1` 契约。第一优先级已完成：桌面 Web“按已确认标签补充候选”入口点击、`includeConfirmedTags=true` 请求和完成/失败回归，并已进入文档关系图最小切片。下一项补文档详情跳转和关系证据定位；当前不同时实现真实标签模型、Embedding、问答或 Agent，移动端、触控和读屏仍顺延。

## 19.1 已完成验收：SQLite 有界连接池与流式抽取主线

### 19.1.1 已完成验收：文本型 PDF 来源资料导入

v0.14 已完成可复制文本 PDF 的最小导入闭环，不同时实现 DOCX、OCR、版面还原或 PDF 表格结构化：

1. **后端解析边界**：使用现有 PDFBox 依赖读取 PDF 原始字节，`kind` 固定为 `pdf`，原始文件仍按 UUID 独立落盘，内容指纹继续基于原始字节计算；解析文本必须保留可反查的页码边界，不能把所有页面无标识拼成一段。
2. **明确失败类型**：损坏 PDF、加密或受密码保护 PDF、零页 PDF，以及不含可提取文本的纯扫描 PDF 都返回逐文件失败结果；纯扫描件提示需要 OCR，但本任务不引入 OCR、外部供应商或临时图片链路。
3. **前后端契约**：更新 Controller/OpenAPI 的文件类型说明、前端文件选择 `accept`、`SourceDocument.kind` 类型、PDF 卡片标签与预览；PDF 原文预览展示服务端提取文本，不在浏览器端重复解析原始文件。
4. **最小验证资料**：增加完全虚构的多页文本 PDF、空白或无文本 PDF、损坏 PDF 和重复导入案例，覆盖成功解析、页码边界、SHA-256 去重、批次部分失败、空间隔离、前端选择与预览。
5. **验收证据与边界**：Java 21 根 Reactor 36 项测试、前端类型检查和生产构建已通过；隔离 HTTP 已验证导入、原文预览、重复识别和 OpenAPI。当前会话未能执行新增 PDF 浏览器回归，后续涉及来源资料 UI 时应补验；文本抽取成功不代表表格结构、图片、阅读顺序或 OCR 已正确恢复。

### 19.1.2 已完成验收：来源资料分页契约与状态恢复

v0.13 已完成分页接口、MyBatis-Plus SQLite 分页、最近运行与最近成功结果分离、当前页批量抽取状态、AI 摘要预览、首屏按钮状态、无结果提示、处理中状态刷新和自动化浏览器回归；当前前端进一步将分页结果改为无限滚动消费，但服务端仍保持稳定的页码契约。下一会话不要重做；仅保留以下低优先级可观测补强：

1. 当前实现固定为 MyBatis-Plus 的 count/分页查询加一条当前页抽取摘要窗口查询；后续若引入 SQL 计数器，再补充单页 1 条与 12 条资料的查询次数对比，防止回归为 N+1。
2. 将本轮临时无头 Chrome 脚本整理为仓库级持续回归前，应先确认测试依赖、浏览器安装方式、临时数据库生命周期和 CI 成本，不能直接把一次性环境脚本提交进产品源码。

### 19.1.3 已完成验收：连接池、流式抽取、来源资料交互与审核质量

以下事项是 AI 抽取链路进入可用状态前的配套任务；第 1～14 项、候选审核持久化、无 Embedding 候选召回、Fake 关系判断、逐字证据校验、后端审核 API、固定资料完整指标评估、标签持久化基础、Fake 标签运行、标签审核后端闭环和桌面 Web 标签联调均已完成各自记录的代码和验证边界，下一优先级进入专项阶段 2 的已确认标签候选补充：

1. **已完成验收：配置数据库连接池**：v0.15 已改用 Spring Boot 自动配置 HikariCP，默认最大连接数 4、最小空闲 1、池获取超时 3 秒；SQLite 每条连接启用 WAL、外键和 5 秒 `busy_timeout`。自动测试验证了池耗尽异常携带稳定池名、WAL 写期间可读取已提交快照、第二写者返回 `SQLITE_BUSY`，以及池上限为 1、Fake 模型阻塞时仍能并发导入和查看资料。该证据只覆盖本地单进程和 Fake 模型，不代表生产负载、多实例或 SQLite 多写能力。
2. **已完成当前自动验证：将 AI 抽取改为流式输出**：v0.16 已在 Spring MVC + `fetch` 链路使用 `text/event-stream`，事件固定为 `run_started`、`chunk_started`、`delta`、`chunk_completed`、`document_summary_started`、`document_summary_completed`、`completed` 和 `error`。前端通过 `ReadableStream` 增量消费；主视图显示中文处理进度、全文摘要阶段和已校验结果，模型原始 JSON 默认折叠为技术详情。服务端只转发供应商真实增量，完整响应通过 Schema、业务引用和逐字证据校验并成功落库后才发送 `completed`；客户端断线不取消后台运行。Java 21 根 Reactor 51 项测试、前端类型检查和生产构建均通过，OpenAPI 已断言 SSE 媒体类型。真实 `gpt-5.4-mini` 流、全文摘要质量、5 秒首事件、网络代理、浏览器视觉和自动重试仍未验证。
3. **已完成桌面 Web 验收：把 AI 输出并入来源资料查看弹窗**：已将“渲染预览/原文预览”扩展为“渲染预览/原文预览/AI 输出”三个 Tab；AI Tab 展示实时文本、分片进度、候选实体/关系/证据/冲突和运行元数据。没有运行记录时显示明确空态；关闭已完成结果后再次打开按 `extractionRunId` 恢复服务端结果；最近失败但有历史成功结果时保留失败提示并展示历史结果；历史读取错误与抽取失败分离；卡片按钮状态统一为“AI 提取”→“提取中…”→“重新提取”，失败为“重试提取”。桌面 Chrome 已验证三 Tab、AI 空态、流式进度、失败/重试、关闭后历史恢复和审核状态恢复；移动端、触控和读屏顺延。
4. **已完成当前自动验证：收窄来源资料卡片、批量操作并简化删除入口**：桌面端三列竖直卡片、中等宽度两列、移动端单列，按“格式与状态 / 名称 / 完整摘要 / 大小与时间 / 按钮”分区，摘要区伸缩以保证元信息与按钮对齐；删除按钮移至右上角 icon-only，包含 `aria-label`、`title` 和现有二次确认。整张卡片可点击或使用 Enter/空格多选，并有卡片级高亮；批量删除由服务端 `deletion-batches` 在事务中执行，批量提取由 `extraction-batches` 交给默认 2 并发有界线程池，每份资料保持独立运行和状态恢复。卡片底部只保留“查看”和 AI 提取操作，历史结果统一在查看弹窗的 AI 输出 Tab 恢复；SHA-256 从卡片迁移到渲染预览 Tab，短值悬浮展示完整值。Java 21 根 Reactor 47 项测试、前端类型检查和生产构建通过；实际多选、批量操作、悬浮、移动端和读屏视觉回归仍待第 5 项补齐。
5. **已完成桌面 Web 当前能力验收**：后端/Fake AI 自动测试已覆盖多分片增量、结构化结果失败、部分 JSON 不落完整结果、失败运行恢复、单连接池并发边界和历史完整结果恢复；桌面 Chrome 进一步覆盖无限滚动、鼠标/键盘多选、批量提取、批量删除确认与取消、三 Tab、AI 空态、失败/手工重试、历史结果与审核恢复，并使用本地 OpenAI-compatible Fake 上游验证真实 HTTP/SSE 分块和上游中断失败边界。当前产品契约仍是“关闭弹窗不取消后台任务，完成后通过运行标识恢复”；尚未实现主动取消、自动断线重连或事件续传，也未验证真实模型流式兼容性和生产反向代理。这些作为后续可靠性边界单独评估；移动端和读屏验收顺延。

6. **已完成验收：来源资料名称模糊搜索**：`GET /api/v1/spaces/{spaceId}/documents` 支持可选 `name` 参数，搜索严格限定当前知识空间并继续复用 MyBatis-Plus 分页；输入变化回到第 1 页，空结果和清空条件均有明确状态。后端对 LIKE 的 `\\`、`%`、`_` 元字符做参数化转义，前端使用 300ms 防抖，避免每个字符请求接口。Java 21 根 Reactor 全量 43 项测试、前端类型检查和生产构建通过；可见浏览器视觉和真实网络请求次数仍待后续回归。

7. **已完成当前自动验证：关系类型白名单与跨分片实体合并评估**：现有实体图谱兼容链路只允许 Prompt 原有的 7 种固定关系及其主体/客体方向；服务端拒绝未知关系和类型方向不匹配关系，完整结果不会静默删减错误项。固定双分片 Fake 资料进一步验证带首尾空格差异的同一实体按 `spaceId + nodeType + normalizedKey` 合并、同一来源标识不重复、同一方向和类型的关系只物化一条，并分别保存两个分片的逐字证据。Java 21 根 Reactor 54 项测试通过；语义近似名称、别名、真实模型遵循度和更复杂跨文档实体消歧未验证，也不在兼容链路中投机实现。

8. **已完成当前自动验证：以二阶段模型汇总生成自然全文摘要**：每份文档完成所有分片抽取后（包括只有一个分片时）新增一次全文汇总模型调用，输入文档名称、业务类型、按原文顺序排列的章节路径和已校验分片摘要，输出一段 1～160 字符的自然中文全文摘要。独立 `document-summary-v1` Prompt 禁止“本分片/当前分片”、YAML、候选标识、机械分号拼接和原文外事实；全文摘要仅用于展示，不作为关系证据。`ai_extraction_runs` 保存摘要 Prompt 版本、`not_started/completed/failed` 状态和失败原因；汇总失败时整次抽取仍为 `completed`，候选事实正常落库，`document_summary` 为空并回退导入原文 excerpt。SSE 新增 `document_summary_started` / `document_summary_completed` 阶段事件，结构化抽取和文本摘要分别使用独立 AI Service，避免 JSON Schema 误套到摘要请求。Java 21 根 Reactor 51 项测试和前端 typecheck/build 已通过；真实模型摘要质量、长度遵循度和跨章节自然度仍未验证。
9. **已完成当前自动验证：无 Embedding 文档候选召回、Fake 关系闭环与固定资料评估**：`DocumentCandidateRecallService` 以 `document-candidate-recall-v1` 固定 TopK=8 执行有效空间过滤、主体排除、显式引用/文件名/标题/章节标题/摘要/正文关键词通道融合、稳定去重和硬负例抑制；`DocumentAssociationService` 再通过 `DocumentAssociationClient` 接入 Fake 判断、候选集合封闭校验、逐字分片证据验证、suggested 幂等物化、运行恢复和批量审核 API。固定 12 份资料的 7 条正例/5 组负例端到端评估达到 Recall@8、关系类型、方向、证据、非 none Precision 和去重门槛，Precision@8 微平均为 0.1707；Java 21 根 Reactor 67 项测试通过。真实资料、真实模型和浏览器前端仍待验证。
10. **已完成当前自动验证：可选标签持久化基础**：新增标签字典、文档标签和标签证据三张表及 MyBatis-Plus 分层；AI 候选只能以 `suggested` 创建且必须保留置信度、抽取运行、Prompt/Schema 版本和逐字证据，用户手工标签只能以 `confirmed` 创建且不伪造模型字段。标签名称只折叠大小写和空格，不做语义同义词合并；同版本稳定键重复运行复用旧候选，Prompt 变化保留新候选。标签专项 4 项和隔离后的 Java 21 根 Reactor 71 项测试通过；真实模型、标签运行、审核 API、前端和浏览器未验证。
11. **已完成当前自动验证：MapStruct 映射收敛**：引入 MapStruct 编译期映射和 Lombok 绑定，将 9 组持久化映射及 4 组接口响应映射从 Repository/ServiceImpl 手写代码中独立出来；统一时间、枚举、可空数值和 JSON 转换，目标字段遗漏在编译期失败。新增 4 项映射边界测试后，Java 21 根 Reactor 全量 75 项测试通过；该重构不改变数据库、接口契约或业务状态机，也不替代真实模型、浏览器和生产联调。
12. **已完成当前自动验证：Fake 标签运行闭环**：新增独立标签运行表、MapStruct 运行映射、供应商无关 `DocumentTaggingClient`、创建/恢复 REST API 和批量原子物化；模型只接收当前来源资料最多 32 个章节感知分片且合计不超过 24,000 字符，输出最多 8 个标签，必须通过 Bean、局部引用和逐字证据三层校验。重复版本运行不重复写入，旧 SQLite 缺少上下文统计列时幂等补列。新增 5 项标签运行测试后 Java 21 根 Reactor 全量 80 项测试通过；该项当时尚未验证审核 API，随后已由第 13 项补齐。真实标签模型、固定资料标签 Precision/Recall、前端和生产仍未验证。
13. **已完成当前自动验证：标签查询与不可变审核闭环**：新增文档级标签查询、批量采纳/拒绝和空间级已确认标签统计 API；响应批量组装标签定义、证据与审核历史，空间统计通过 MyBatis XML Join 只计算 `confirmed` 标签和有效来源资料。审核请求只接受服务端文档标签关系标识、动作和可选原因，先验证同批重复、空间/文档归属和 `suggested` 当前态，再在同一事务中更新状态并插入唯一审核历史；跨文档批次、重复标识或重复审核不会产生部分结果。新增 3 项测试后 Java 21 根 Reactor 全量 83 项测试通过；该项当时尚未验证桌面 Web 和浏览器，随后已由第 14 项补齐。真实标签模型、固定资料标签 Precision/Recall 和生产仍未验证。
14. **已完成桌面 Web 标签联调**：新增最近标签运行只读契约，前端“AI 输出”内提供独立文档标签层，支持真实空态、同步运行处理中、完成/失败状态、逐字证据、单条和批量审核、审核冲突服务端回读、刷新恢复及实体关系兼容页切换；左侧只消费空间 confirmed 标签统计。前端 typecheck/build、Java 21 根 Reactor 全量 83 项和隔离桌面 Chrome 回归通过；Chrome 成功/处理中态使用构造的纯虚构 SQLite 运行快照，未验证真实标签模型、固定资料标签 Precision/Recall、移动端或生产。

### 19.1.4 后续 RAG / Embedding 增强计划

本节不阻塞默认文档内容关联。只有完成非向量内容关联、固定样本评估并确认存在召回缺口后，才按以下顺序评估 RAG/Embedding：

1. 在本地通过环境变量提供 `AI_API_KEY`，对 `gpt-5.4-mini` 执行一条受控的真实结构化抽取请求，验证 Chat Completions 协议、模型权限、超时、重试和 Prompt 结构化输出；不得把密钥写入仓库。
2. 确认自定义端点实际支持的 Embedding 模型，再启用 `AI_EMBEDDING_ENABLED`；分别验证文档向量化、查询向量化、维度一致性和最小相似度检索。
3. 建立 `document_sections`、`document_chunks` 和 `ai_extraction_runs`，持久化章节、分片、Embedding 缓存、模型/Prompt/Schema 版本和失败上下文。
4. 在非向量内容关联存在可证明的召回缺口后，将 PRD Markdown 章节解析和分片接入 RAG 检索上下文，再调用 `AiExtractionClient` 进行候选判断。
5. **已完成当前自动验证**：将 AI 候选节点、候选关系和证据幂等保存到当前知识空间，关系默认保持 `suggested`，不直接生成正式关系。
6. **已完成当前自动验证**：由 AI 抽取结果弹窗提交单条/批量接受或拒绝，服务端更新 `graph_edges`、记录 `review_actions`，并支持审核状态恢复；独立待审核关系列表不再实施。
7. 保持当前来源资料、图谱查询和空间隔离边界，补充模型不可用、召回为空、结构化输出非法、证据无效和重试错误态。

### 19.1.5 已确认的文档主线入口与执行顺序

本节记录 2026-08-20 已确认的产品决策，作为后续验证和开发入口：

1. **主视图收敛**：后续默认业务主视图围绕“文档关联 + 可选标签增强”组织；文档关系图节点必须来自真实 `source_documents`。
2. **取消图谱类型主线**：不再在左侧固定展示“图谱类型”，也不把节点类型筛选作为文档关联主线的验收项；现有 `NodeType`、历史实体、数据库字段和兼容查询暂不删除，避免破坏已有抽取结果。
3. **默认内容关联、标签可选**：新文档分析优先产出全文摘要、基于标题/摘要/正文/显式引用/关键词的文档候选关联和证据；候选标签及标签关联作为可选增强，不是关联成立或执行关联分析的前置条件。项目、部门、人员、任务等实体抽取保留为兼容或实验能力，不作为文档关联闭环的必选前置步骤。
4. **左侧信息架构**：已将“图谱类型”区域替换为“待处理 + 标签”；标签区只消费空间级 `confirmed` 标签及有效来源资料数量，不伪造 suggested/rejected 标签或关联数量。当前尚未提供标签筛选，也不恢复图谱类型筛选；如果未来兼容旧实体图谱确有需要，必须作为局部工具栏单独评估。
5. **当前验证顺序**：
   - 已完成桌面 Web 真实 Chrome 下的来源资料无限滚动、卡片多选、批量操作、三 Tab、空态、历史恢复、错误/重试和上游连接中断验证；移动端、触控和读屏顺延到后续专项；
   - 已完成现有实体关系白名单、主体/客体方向和跨分片精确规范化键合并的 Fake/SQLite 验收；
   - 已完成专项阶段 0，冻结 12 份资料、7 条正例、5 组负例、7 个召回用例、Prompt/Schema/策略版本、方向和验收规程；
   - 已完成阶段 1 的文档关联运行、关系、证据和审核持久化基础、无 Embedding 候选召回、Fake 关系判断、候选集合/逐字证据校验、关联审核 API 和固定资料完整关系指标评估；阶段 2 已完成标签持久化基础、独立 Fake 标签运行、Schema/业务/证据校验、suggested 幂等物化、运行恢复、标签查询、不可变审核历史、批量审核 API、桌面 Web 标签联调和 confirmed 标签显式补充候选后端/前端契约；阶段 3 文档关系图最小切片已完成独立查询、confirmed 边过滤、真实来源文档节点、桌面切换和空态验证，下一步补文档详情跳转与关系证据定位；
   - 在具备真实模型密钥和受控预算时，单独补充二阶段自然全文摘要与 `prd-extraction-v3` 关系约束的真实模型质量边界，不以 Fake 结果代替真实模型结论，也不阻塞候选召回；
   - 之后按“文档关系判断 → 证据校验 → 关联审核 → 可选标签生成/审核/关联 → 文档关系图 → RAG/Embedding 评估”的顺序实现。
6. **禁止提前扩张**：阶段 2 只允许按冻结契约新增标签持久化、候选、证据和审核能力；Embedding 索引、文档关系图切换、会话引用和 Agent 编排仍等待各自阶段。

## 19.2 后续主线

1. **已接入当前基础链路**：前端优先加载后端图谱查询结果；空知识空间展示真实空态，等待资料导入和 AI 抽取产生正式图谱事实。
2. 实现孤立、失效来源、缺字段和冲突检查。
3. 在文本型 PDF 首版完成后增加 DOCX 解析，并继续实现增量导入和 Markdown/JSON/PNG 导出；扫描 PDF OCR 仍不进入当前首版。
4. **阶段 1 已完成，进入阶段 2**：默认主线为文档内容关联，标签关联可选；不再保留独立图谱类型导航或把实体类型识别作为新增主线前置；现有实体图谱保留兼容和实验用途。专项 PRD、固定资料、标注答案、关系方向、五种关系优先级、版本契约、持久化基础、无 Embedding 候选召回、Fake 判断、证据校验、后端审核 API 和固定资料完整指标评估已完成。
5. **专项方案实施顺序**：可选标签持久化、独立 Fake 标签运行、Schema/业务/证据校验、suggested 幂等物化、运行恢复、标签查询、不可变审核历史、批量审核 API、桌面 Web 标签联调、confirmed 标签显式补充候选通道、固定资料质量对照、入口浏览器回归和独立文档关系图最小切片已经完成；下一步补文档详情跳转和关系证据定位，最后评估是否引入向量召回和混合 RAG。固定 Fake 关系基线已经达标，但 Precision@8 0.1707 仍作为后续降噪对照，不把共同标签或向量相似度当作正式关系依据。
6. **未来 Agent 扩展边界**：仅当出现动态工具选择、长流程暂停/恢复、复杂多步骤编排或可回放工作流需求时，才评估在专项 PRD 第 14 节定义的 `AgentOrchestrator`；当前标签与关联主线继续使用固定 Pipeline，不能为了引入 Agent 而绕过领域 Service、证据校验或审核状态机。

## 19.3 提交约定

每次形成 Git 提交时，必须先同步更新本 PRD 和 `docs/roadmap.md`：

1. 更新 `version`、`status` 和当前日期。
2. 在“已完成”中记录本次已落地且已验证的能力。
3. 在“当前验证边界”中记录未验证或仅有骨架的部分。
4. 在“下一步代办”中保留下一次新会话可以直接执行的首要任务。
5. 在路线图当前阶段中细化已完成子项、对应证据、剩余风险和下一任务顺序，确保与 PRD 一致。
6. 完成敏感信息扫描和实际 Git diff 检查后再提交。

详细执行规则以项目根目录 `AGENTS.md` 为准。允许在形成独立、必要、可回滚的阶段成果时主动进行本地小提交；未经明确授权不得推送。

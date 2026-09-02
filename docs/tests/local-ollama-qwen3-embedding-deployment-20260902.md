# Ollama qwen3-embedding 本地部署验证记录

- 验证日期：2026-09-02 10:19:07 CST
- 本机硬件：MacBook Pro，Apple M1 Pro，arm64，8 核 CPU，16 GB 统一内存
- 安装方式：Homebrew `brew install ollama`
- 服务启动方式：`brew services start ollama`
- Ollama 版本：0.33.0
- 本地服务地址：`http://127.0.0.1:11434`
- OpenAI-compatible Base URL：`http://127.0.0.1:11434/v1`
- 模型 tag：`qwen3-embedding:latest`
- 模型 ID：`64b933495768`
- 模型文件大小：4.7 GB
- 实测向量维度：4096
- 建议项目配置版本：`ollama-qwen3-embedding-latest-20260902`

## 接口自检

| 验证项 | 结果 | 证据摘要 |
| --- | --- | --- |
| Ollama 服务版本 | 通过 | `/api/version` 返回 `{"version":"0.33.0"}` |
| 模型列表 | 通过 | `ollama list` 显示 `qwen3-embedding:latest`，大小 4.7 GB |
| 原生 `/api/embed` | 通过 | 1 条输入返回 1 条向量，维度 4096，无 error 字段 |
| OpenAI-compatible `/v1/embeddings` | 通过 | 1 条输入返回 `object=list`、`data_count=1`、`index=0`、维度 4096 |
| OpenAI-compatible 批量输入 | 通过 | 2 条输入返回 2 条向量，index 为 0、1，维度均为 4096 |
| 向量基础合法性 | 通过 | `jq` 检查首条向量元素均为 JSON number，维度 4096 |

## 耗时

- 原生 `/api/embed` 单条短文本耗时：7.499 秒（包含首次模型加载）
- OpenAI-compatible `/v1/embeddings` 单条短文本耗时：0.709 秒（模型已加载）
- OpenAI-compatible `/v1/embeddings` 两条批量输入耗时：0.515 秒
- 项目 Java 客户端两条批量输入耗时：972 ms

## 后端 smoke

- 编译验证：通过，`JAVA_HOME` 指向 Java 21 后执行 `mvn -q -pl server -am -DskipTests test-compile dependency:build-classpath`
- 项目客户端 smoke：通过，使用 `OpenAiCompatibleDocumentEmbeddingClient` 包装 LangChain4j `OpenAiEmbeddingModel`，通过 `http://127.0.0.1:11434/v1` 获取 2 条向量
- 客户端描述：`EmbeddingModelDescriptor[provider=openai-compatible, model=qwen3-embedding:latest, version=ollama-qwen3-embedding-latest-20260902, dimension=4096]`
- 项目客户端返回：`vector_count=2`，`dimensions=[4096, 4096]`，`all_finite=true`
- 单元测试：通过，`mvn -q -pl server -Dtest=OpenAiCompatibleDocumentEmbeddingClientTests test`

## 未覆盖边界

- 未运行 `RealAiSmokeIntegrationTests`：该测试同时调用聊天模型和 Embedding，本次只部署本地向量模型，未拉取本地聊天模型。
- 未运行 MySQL 语义评估测试：现有评估会清理多张业务表，未确认目标测试库前不执行数据库清理。
- 未验证 Milvus、本地接口链路、真实业务资料、生产环境或并发压力。
- 本次只证明本机 Ollama Embedding 服务、OpenAI-compatible 协议、项目 Java 客户端封装和基础向量约束可用，不证明 RAG 召回质量。

## 本地配置建议

```bash
export AI_ENABLED=true
export AI_PROVIDER=openai-compatible
export AI_BASE_URL=http://127.0.0.1:11434/v1
export AI_API_KEY=ollama
export AI_MODEL=qwen3:latest

export AI_EMBEDDING_ENABLED=true
export AI_EMBEDDING_BASE_URL=http://127.0.0.1:11434/v1
export AI_EMBEDDING_API_KEY=ollama
export AI_EMBEDDING_MODEL=qwen3-embedding:latest
export AI_EMBEDDING_DIMENSION=4096
export AI_EMBEDDING_VERSION=ollama-qwen3-embedding-latest-20260902
```

如果只验证 Embedding 客户端，不触发聊天抽取、标签或问答，可暂时不拉取 `qwen3:latest`；一旦运行会调用聊天模型的功能或 `RealAiSmokeIntegrationTests`，需要先安装对应本地聊天模型，或改用已可用的 OpenAI-compatible 聊天端点。

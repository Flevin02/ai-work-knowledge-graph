# Ollama + qwen3-embedding 本地部署交接文档

## 1. 目标与边界

本文档给另一个线程执行本机 Embedding 环境准备，目标是在本机启动 Ollama，并提供与当前后端兼容的 OpenAI-compatible Embedding 接口。

当前建议目标模型：

- 运行时：Ollama
- 模型：`qwen3-embedding:latest`
- 本地服务：`http://127.0.0.1:11434`
- OpenAI-compatible Base URL：`http://127.0.0.1:11434/v1`

本交接只负责本地模型服务安装、模型拉取、接口自检和项目配置建议，不执行数据库清理、不删除旧向量、不重建 MySQL 中的 `document_chunk_index_states`，也不把新旧 Embedding 向量混用。

## 2. 当前本机条件

已只读检查本机配置：

- 设备：MacBook Pro
- 芯片：Apple M1 Pro
- CPU：8 核
- GPU：14 核
- 内存：16 GB 统一内存
- 架构：arm64
- 系统：macOS 26.5.2

该配置适合本地运行 Embedding 服务和小规模实验。Ollama 模型页在 2026-09-02 标注 `qwen3-embedding:latest` 为 8B 模型，文件约 4.7 GB，上下文约 40K；适合先做固定资料集、本地 smoke test 和少量文档分片向量化。不要同时把 Next.js、Spring Boot、MySQL、浏览器和大并发 Embedding 压到很高负载。

## 3. 为什么优先 Ollama

当前后端已经通过 `DocumentEmbeddingClient` 抽象隔离供应商，并用 LangChain4j `OpenAiEmbeddingModel` 调 OpenAI-compatible `/v1/embeddings`。Ollama 提供本地 `/api/embed`，也提供 OpenAI-compatible `/v1/embeddings`，因此首选不改 Java 客户端，只换本地 Base URL、模型名和维度配置。

这条路线的收益是：

- 不再依赖阿里云百炼 Embedding 计费。
- API Key 可使用本地占位值，不产生外部计费。
- 后端仍保持 `provider/model/version/dimension` 记录，不破坏已有向量版本隔离。
- 出问题时可以关闭 `AI_EMBEDDING_ENABLED`，回到非向量候选召回。

## 4. 安装线程执行清单

### 4.1 安装 Ollama

优先使用 Ollama 官方 macOS 安装包：

```bash
open https://ollama.com/download
```

安装完成后检查命令是否可用：

```bash
ollama --version
```

如果安装线程更习惯 Homebrew，也可以先确认本机 formula 后再安装：

```bash
brew info ollama
brew install ollama
```

### 4.2 启动本地服务

如果使用官方 App，打开 Ollama 后通常会在本机启动 `11434` 服务。也可以在终端启动：

```bash
ollama serve
```

另开终端检查服务：

```bash
curl -s http://127.0.0.1:11434/api/version
```

期望返回 JSON，至少包含 `version` 字段。

### 4.3 拉取模型

```bash
ollama pull qwen3-embedding:latest
```

检查本机模型列表：

```bash
ollama list
```

期望能看到 `qwen3-embedding:latest`。

### 4.4 验证 Ollama 原生 Embedding 接口

```bash
curl -s http://127.0.0.1:11434/api/embed \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "qwen3-embedding:latest",
    "input": "这是 AI 工作知识图谱的本地 Embedding 自检文本。"
  }'
```

期望返回向量数组。不要把完整向量写入文档或聊天，只需要记录是否成功、耗时和维度。

### 4.5 验证 OpenAI-compatible Embedding 接口

当前 Java 后端优先使用这个接口：

```bash
curl -s http://127.0.0.1:11434/v1/embeddings \
  -H 'Authorization: Bearer ollama' \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "qwen3-embedding:latest",
    "input": "这是 AI 工作知识图谱的 OpenAI-compatible Embedding 自检文本。"
  }'
```

期望返回 OpenAI-compatible 结构：

```text
data[0].embedding = float 数组
```

安装线程必须实测 `data[0].embedding` 的长度，然后再填写 `AI_EMBEDDING_DIMENSION`。不要只按网页说明猜维度。

可用 `jq` 检查维度：

```bash
curl -s http://127.0.0.1:11434/v1/embeddings \
  -H 'Authorization: Bearer ollama' \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "qwen3-embedding:latest",
    "input": "dimension check"
  }' | jq '.data[0].embedding | length'
```

## 5. 后端本地配置建议

当前项目真实 Embedding Bean 受 `ai.enabled=true`、`ai.api-key` 非空和 `ai.embedding-enabled=true` 共同影响。因此只启用本地 Embedding 时，也需要给聊天模型配置一个本地占位值或已安装的本地聊天模型。

建议安装线程先在本地 shell 或未提交的本地配置中使用：

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
export AI_EMBEDDING_DIMENSION=<用 curl 实测的维度>
export AI_EMBEDDING_VERSION=ollama-qwen3-embedding-latest-20260902
```

注意：

- 如果没有安装 `qwen3:latest` 聊天模型，单纯创建 `ChatModel` Bean 通常不会立即请求模型；但调用真实聊天抽取、标签或问答时会失败。只做 Embedding 验证时，可以先不触发聊天模型功能。
- 如果要同时做本地聊天模型验证，需要另行 `ollama pull qwen3:latest`，这不属于本文档的最小 Embedding 交接范围。
- `AI_EMBEDDING_VERSION` 必须随模型来源、tag、维度或运行策略变化而升级，避免把新旧向量视作同一批事实。

## 6. 项目验证建议

安装线程完成 Ollama 自检后，再回到本项目执行最小 Java 验证。

### 6.1 编译和默认回归

默认测试仍应使用 Fake Embedding，不依赖 Ollama：

```bash
cd backend
kg_java_home=$(/usr/libexec/java_home -v 21)
test -n "${MYSQL_PASSWORD:-}" || { echo '请先在当前终端设置 MYSQL_PASSWORD'; exit 1; }
JAVA_HOME="$kg_java_home" PATH="$kg_java_home/bin:$PATH" mvn -pl server -am test
```

通过只能说明默认 Fake 自动回归正常，不说明本地 `qwen3-embedding:latest` 已接通。

### 6.2 本地真实 Embedding smoke

安装线程应优先验证已有真实 Embedding 测试或实验入口。如果当前仓库没有专门的 Ollama smoke test，则先不要扩大实现，只记录阻塞点，再由主开发线程补一个最小 `real-ai` 或本地 Embedding profile 测试。

最小验收应至少证明：

- 后端通过 `http://127.0.0.1:11434/v1/embeddings` 拿到向量。
- 返回数量与输入文本数量一致。
- 实测维度与 `AI_EMBEDDING_DIMENSION` 一致。
- 向量只包含有限 `float` 值。
- `EmbeddingModelDescriptor` 写入的是 `openai-compatible / qwen3-embedding:latest / ollama-qwen3-embedding-latest-20260902 / 实测维度`。

## 7. 数据隔离和回滚

切换 Embedding 模型后不能混用旧向量。当前项目已经记录：

- `embedding_provider`
- `embedding_model`
- `embedding_version`
- `dimension`
- `content_hash`
- `vector_json`

安装线程不要删除旧记录。正确做法是新增版本，验证通过后再由主开发线程决定是否为特定资料重建向量。回滚方式：

```bash
export AI_EMBEDDING_ENABLED=false
```

关闭后，默认业务链路应退回显式引用、标题、摘要和关键词等非向量候选召回。

## 8. 交付回填

安装线程完成后，请回填以下信息到新的 `docs/tests/` 验证文档，不要写进 PRD 或 Roadmap：

```text
验证日期：
本机硬件：
Ollama 版本：
模型 tag：
模型文件大小：
原生 /api/embed 是否通过：
OpenAI-compatible /v1/embeddings 是否通过：
实测向量维度：
单条短文本耗时：
批量输入是否通过：
后端 smoke 是否通过：
失败信息摘要：
结论：
```

## 9. 参考资料

- Ollama 下载：https://ollama.com/download
- Ollama API 文档：https://docs.ollama.com/api
- Ollama OpenAI compatibility：https://docs.ollama.com/openai
- Ollama qwen3-embedding 模型页：https://ollama.com/library/qwen3-embedding
- Qwen3 Embedding 模型页：https://huggingface.co/Qwen/Qwen3-Embedding-8B

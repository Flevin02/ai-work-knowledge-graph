package com.flevin.knowgraph.server.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 模型配置，分别管理聊天模型、Embedding 模型和可追溯版本信息。
 */
@Data
@ConfigurationProperties(prefix = "ai")
public class AiProperties {

    /**
     * 是否创建真实 AI 模型客户端；默认关闭以保证无密钥测试可运行。
     */
    private boolean enabled;

    /**
     * 当前模型供应商。
     */
    private String provider = "openai-compatible";

    /**
     * 结构化抽取使用的聊天模型。
     */
    private String model = "gpt-5.4-mini";

    /**
     * 文档和查询向量化使用的 Embedding 模型。
     */
    private String embeddingModel = "text-embedding-3-small";

    /**
     * 是否启用真实 Embedding 模型；聊天抽取和 RAG 向量化可以分阶段启用。
     */
    private boolean embeddingEnabled;

    /**
     * 真实 Embedding 模型的显式向量维度；首个返回向量必须与该值一致，不匹配时整批失败。
     */
    private int embeddingDimension = 1536;

    /**
     * 真实 Embedding 实验版本；更换模型或维度时必须显式升级，禁止新旧向量混用。
     */
    private String embeddingVersion = "openai-v1";

    /**
     * 模型供应商 API Key。
     */
    private String apiKey;

    /**
     * 可选的供应商 Base URL。
     */
    private String baseUrl = "https://api.psydo.top/v1";

    /**
     * Embedding 可选的独立 Base URL；为空时回退到聊天端点的 baseUrl。
     */
    private String embeddingBaseUrl;

    /**
     * Embedding 可选的独立 API Key；为空时回退到聊天端点的 apiKey。
     */
    private String embeddingApiKey;

    /**
     * 获取 Embedding 实际使用的 Base URL，独立配置为空时回退聊天端点。
     *
     * @return 非空 Embedding Base URL
     */
    public String effectiveEmbeddingBaseUrl() {
        return embeddingBaseUrl == null || embeddingBaseUrl.isBlank() ? baseUrl : embeddingBaseUrl.strip();
    }

    /**
     * 获取 Embedding 实际使用的 API Key，独立配置为空时回退聊天端点。
     *
     * @return 非空 Embedding API Key
     */
    public String effectiveEmbeddingApiKey() {
        return embeddingApiKey == null || embeddingApiKey.isBlank() ? apiKey : embeddingApiKey.strip();
    }

    /**
     * OpenAI-compatible 端点是否真实支持 JSON Schema 响应格式。
     */
    private boolean jsonSchemaEnabled;

    /**
     * 当前结构化抽取 Prompt 版本。
     */
    private String promptVersion = "prd-extraction-v3";

    /**
     * 文档级全文摘要 Prompt 版本。
     */
    private String summaryPromptVersion = "document-summary-v1";

    /**
     * 当前结构化输出 Schema 版本。
     */
    private String schemaVersion = "extraction-v1";

    /**
     * 抽取温度；确定性结构化抽取默认使用零温度。
     */
    private double temperature = 0.0D;

    /**
     * 单次抽取允许的最大输出 Token。
     */
    private int maxOutputTokens = 4096;

    /**
     * 模型传输或短暂服务失败时的最大重试次数。
     */
    private int maxRetries;

    /**
     * 单次模型请求超时秒数。
     */
    private int timeoutSeconds = 60;
}

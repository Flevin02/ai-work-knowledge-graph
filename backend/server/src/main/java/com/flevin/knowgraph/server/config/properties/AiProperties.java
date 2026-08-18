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
     * 模型供应商 API Key。
     */
    private String apiKey;

    /**
     * 可选的供应商 Base URL。
     */
    private String baseUrl = "https://api.psydo.top/v1";

    /**
     * OpenAI-compatible 端点是否真实支持 JSON Schema 响应格式。
     */
    private boolean jsonSchemaEnabled;

    /**
     * 当前结构化抽取 Prompt 版本。
     */
    private String promptVersion = "prd-extraction-v1";

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

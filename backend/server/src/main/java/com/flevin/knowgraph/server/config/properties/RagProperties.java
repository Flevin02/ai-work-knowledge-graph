package com.flevin.knowgraph.server.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG 分片和检索参数，显式保存默认值以便后续实验比较。
 */
@Data
@ConfigurationProperties(prefix = "ai.rag")
public class RagProperties {

    /**
     * 章节解析规则版本；规则语义变化时必须显式升级。
     */
    private String sectionParserVersion = "prd-markdown-section-v1";

    /**
     * 章节感知分片策略版本；算法语义变化时必须显式升级。
     */
    private String chunkStrategyVersion = "section-aware-v1";

    /**
     * 单个文本分片最大字符数。
     */
    private int maxChunkChars = 1500;

    /**
     * 相邻长分片之间保留的重叠字符数。
     */
    private int overlapChars = 150;

    /**
     * 默认召回的候选分片数量。
     */
    private int topK = 5;

    /**
     * 默认最低相似度分数，仅作为初始实验参数。
     */
    private double minScore = 0.70D;
}

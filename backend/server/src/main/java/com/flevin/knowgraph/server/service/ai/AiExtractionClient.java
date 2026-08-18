package com.flevin.knowgraph.server.service.ai;

import com.flevin.knowgraph.server.model.ai.AiExtractionRequest;
import com.flevin.knowgraph.server.model.ai.AiExtractionResult;

/**
 * AI 结构化抽取领域接口，隔离 LangChain4j 和具体模型供应商。
 */
public interface AiExtractionClient {

    /**
     * 从单个可追溯来源分片中提取实体、关系、证据和冲突候选。
     *
     * @param request 包含来源定位和原文的抽取请求
     * @return 经过结构和证据校验的候选结果
     */
    AiExtractionResult extract(AiExtractionRequest request);
}

package com.flevin.knowgraph.server.service.ai;

import com.flevin.knowgraph.server.model.ai.AiExtractionRequest;
import com.flevin.knowgraph.server.model.ai.AiExtractionResult;
import com.flevin.knowgraph.server.model.ai.AiDocumentSummaryRequest;

import java.util.function.Consumer;

/**
 * AI 结构化抽取领域接口，隔离 LangChain4j 和具体模型供应商。
 */
public interface AiExtractionClient {

    /**
     * 根据已校验的分片摘要生成一段自然的文档级全文摘要。
     *
     * @param request 包含来源资料定位和按原文顺序排列的分片摘要
     * @return 不超过 160 个字符的文档级摘要；不支持该能力的客户端可抛出运行时异常
     */
    default String summarize(AiDocumentSummaryRequest request) {
        throw new UnsupportedOperationException("当前 AI 客户端不支持文档级全文摘要");
    }

    /**
     * 从单个可追溯来源分片中提取实体、关系、证据和冲突候选。
     *
     * @param request 包含来源定位和原文的抽取请求
     * @return 经过结构和证据校验的候选结果
     */
    AiExtractionResult extract(AiExtractionRequest request);

    /**
     * 从单个可追溯来源分片中流式提取候选结果。
     *
     * <p>默认实现保持不支持模型增量的客户端兼容：仍返回真实完整结果，但不会伪造增量文本。
     * 支持流式协议的适配器应覆盖本方法，并把供应商返回的原始文本增量交给调用方。</p>
     *
     * @param request 包含来源定位和原文的抽取请求
     * @param deltaConsumer 模型原始文本增量消费者；供应商没有增量时不调用
     * @return 经过结构和证据校验的候选结果
     */
    default AiExtractionResult extract(
            AiExtractionRequest request,
            Consumer<String> deltaConsumer
    ) {
        // 回退到完整结果调用，保持不支持流式输出的 Fake 或其他供应商实现可用
        return extract(request);
    }
}

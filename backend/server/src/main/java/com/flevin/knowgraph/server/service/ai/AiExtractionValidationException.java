package com.flevin.knowgraph.server.service.ai;

/**
 * 模型输出未通过结构、引用或证据校验时抛出的异常。
 */
public class AiExtractionValidationException extends RuntimeException {

    public AiExtractionValidationException(String message) {
        super(message);
    }
}

package com.flevin.knowgraph.server.service.conversation;

/**
 * 表示问答模型已返回响应，但结构化内容不满足领域回答契约。
 *
 * <p>该异常与模型连接、认证或供应商服务异常分开，使业务层能够记录稳定的
 * {@code answer_invalid_output} 类别，同时继续保留已经提交的用户消息。</p>
 */
public class ConversationAnswerInvalidOutputException extends RuntimeException {

    /**
     * 创建带稳定原因摘要的非法输出异常。
     *
     * @param message 不包含模型原始响应的安全错误摘要
     */
    public ConversationAnswerInvalidOutputException(String message) {
        super(message);
    }

    /**
     * 创建保留框架解析根因但不暴露模型原文的非法输出异常。
     *
     * @param message 不包含模型原始响应的安全错误摘要
     * @param cause LangChain4j 结构化输出解析根因
     */
    public ConversationAnswerInvalidOutputException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}

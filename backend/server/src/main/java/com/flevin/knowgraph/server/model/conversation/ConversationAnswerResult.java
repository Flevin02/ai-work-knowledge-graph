package com.flevin.knowgraph.server.model.conversation;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 供应商无关的问答结果。
 *
 * <p>回答只是候选内容：引用必须全部通过服务端逐字反查后才会持久化；
 * 引用全部失败或缺失时回答将被标记为 insufficient_evidence，
 * 不能把通过校验前的回答展示为可信结论。</p>
 *
 * @param answer 面向用户的自然语言回答
 * @param citations 回答引用的候选分片引用
 */
@Schema(description = "问答客户端结果")
public record ConversationAnswerResult(
        @Schema(description = "面向用户的回答") String answer,
        @Schema(description = "候选引用列表") List<ConversationAnswerCitation> citations
) {

    public ConversationAnswerResult {
        // 候选引用统一不可变，保证客户端结果不可被调用方篡改
        citations = List.copyOf(citations);
    }
}

package com.flevin.knowgraph.server.model.conversation;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 问答客户端输出的候选引用。
 *
 * <p>客户端只返回局部分片标识和原文片段；偏移为可选项，若提供必须与
 * 原文片段在分片内的实际位置一致，服务端会逐条逐字反查后生成数据库标识。</p>
 *
 * @param chunkId 引用分片在文档内的稳定标识
 * @param quote 原文片段，必须在分片原文中逐字存在
 * @param startOffset 可选原文起始偏移，半开区间
 * @param endOffset 可选原文结束偏移，不包含该位置字符
 */
@Schema(description = "问答候选引用")
public record ConversationAnswerCitation(
        @Schema(description = "引用分片标识", example = "chunk-3") String chunkId,
        @Schema(description = "原文片段") String quote,
        @Schema(description = "可选原文起始偏移") Integer startOffset,
        @Schema(description = "可选原文结束偏移") Integer endOffset
) {
}

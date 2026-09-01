package com.flevin.knowgraph.server.model.conversation;

import com.flevin.knowgraph.server.model.ai.rag.DocumentChunk;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 供应商无关的问答请求。
 *
 * <p>上下文分片由服务端召回并提供；客户端只能引用分片集合内的 chunkId，
 * 不得自行填写文档标识、文件名或页码，数据库标识和引用校验始终由服务端完成。</p>
 *
 * @param question 用户问题文本
 * @param contextChunks 服务端提供的上下文分片，按文档顺序排列
 */
@Schema(description = "问答客户端请求")
public record ConversationAnswerRequest(
        @Schema(description = "用户问题", example = "年会活动方案里场地定在哪里？") String question,
        @Schema(description = "服务端召回的上下文分片") List<DocumentChunk> contextChunks
) {

    public ConversationAnswerRequest {
        // 分片列表统一不可变，防止客户端实现修改服务端召回结果
        contextChunks = List.copyOf(contextChunks);
    }
}

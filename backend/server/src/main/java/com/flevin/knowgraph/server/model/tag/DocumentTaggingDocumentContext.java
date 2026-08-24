package com.flevin.knowgraph.server.model.tag;

import com.flevin.knowgraph.server.model.ai.rag.DocumentChunk;

import java.util.List;

/**
 * 提供给标签客户端的安全来源资料上下文。
 *
 * @param documentId 服务端来源资料标识
 * @param name 来源资料名称
 * @param kind 文件格式
 * @param documentType 文档业务类型
 * @param contentHash 本次运行使用的内容指纹
 * @param chunks 允许模型引用的章节感知分片
 */
public record DocumentTaggingDocumentContext(
        String documentId,
        String name,
        String kind,
        String documentType,
        String contentHash,
        List<DocumentChunk> chunks
) {

    public DocumentTaggingDocumentContext {
        chunks = List.copyOf(chunks);
    }
}

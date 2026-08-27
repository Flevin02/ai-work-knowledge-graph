package com.flevin.knowgraph.server.model.association;

import com.flevin.knowgraph.server.model.ai.rag.DocumentChunk;

import java.util.List;

/**
 * 提供给文档关联判断客户端的安全文档上下文。
 *
 * @param documentId 服务端来源资料标识
 * @param name 来源资料名称
 * @param kind 文件格式
 * @param documentType 文档业务类型
 * @param contentHash 本次分析使用的内容指纹
 * @param summary 最近成功生成的自然摘要；没有时使用导入预览
 * @param confirmedTags 当前文档已确认标签；只在显式开启标签通道时提供
 * @param chunks 允许模型引用的可追溯原文分片
 */
public record DocumentAssociationDocumentContext(
        Long documentId,
        String name,
        String kind,
        String documentType,
        String contentHash,
        String summary,
        List<String> confirmedTags,
        List<DocumentChunk> chunks
) {

    public DocumentAssociationDocumentContext {
        confirmedTags = List.copyOf(confirmedTags);
        chunks = List.copyOf(chunks);
    }
}

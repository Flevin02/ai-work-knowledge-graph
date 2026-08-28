package com.flevin.knowgraph.server.service.ai.embedding;

import com.flevin.knowgraph.server.model.ai.embedding.DocumentEmbeddingIndexResult;

/**
 * 文档分片向量索引服务，负责复用兼容向量并原子写入完整新批次。
 */
public interface DocumentEmbeddingIndexService {

    /**
     * 为一份来源资料的当前分片版本生成缺失向量事实。
     *
     * @param spaceId 知识空间标识
     * @param sourceDocumentId 来源资料标识
     * @return 当前版本分片总数、复用数量和新增索引数量
     */
    DocumentEmbeddingIndexResult indexDocument(
            Long spaceId,
            Long sourceDocumentId
    );
}

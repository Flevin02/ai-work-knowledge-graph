package com.flevin.knowgraph.server.service.ai.embedding;

import com.flevin.knowgraph.server.model.ai.embedding.EmbeddingModelDescriptor;
import com.flevin.knowgraph.server.model.ai.embedding.EmbeddingVector;

import java.util.List;

/**
 * 文档分片和查询文本的 Embedding 领域抽象。
 *
 * <p>业务层只依赖该接口，不直接依赖 LangChain4j 或具体供应商对象；调用方仍需校验返回数量、
 * 模型描述、维度和证据归属后才能持久化或参与候选排序。</p>
 */
public interface DocumentEmbeddingClient {

    /**
     * 获取本次向量化使用的模型和版本快照。
     *
     * @return 可写入索引事实的模型描述
     */
    EmbeddingModelDescriptor descriptor();

    /**
     * 按输入顺序批量生成 Embedding 向量。
     *
     * @param texts 待向量化的非空文本列表
     * @return 与输入一一对应的向量列表
     */
    List<EmbeddingVector> embed(List<String> texts);
}

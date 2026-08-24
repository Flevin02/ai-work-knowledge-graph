package com.flevin.knowgraph.server.service.tag;

import com.flevin.knowgraph.server.model.tag.DocumentTaggingRequest;
import com.flevin.knowgraph.server.model.tag.DocumentTaggingResult;

/**
 * 文档标签抽取客户端，隔离固定 Pipeline 与具体模型、框架和供应商协议。
 */
public interface DocumentTaggingClient {

    /**
     * 从服务端限定的当前文档分片中生成结构化标签候选。
     *
     * @param request 当前来源资料、可引用分片和版本快照
     * @return document-tag-v1 结构化标签结果
     */
    DocumentTaggingResult tag(DocumentTaggingRequest request);
}

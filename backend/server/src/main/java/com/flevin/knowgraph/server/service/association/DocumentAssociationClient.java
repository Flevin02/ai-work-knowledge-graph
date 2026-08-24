package com.flevin.knowgraph.server.service.association;

import com.flevin.knowgraph.server.model.association.DocumentAssociationRequest;
import com.flevin.knowgraph.server.model.association.DocumentAssociationResult;

/**
 * 文档关联判断客户端，隔离固定 Pipeline 与具体模型、框架和供应商协议。
 */
public interface DocumentAssociationClient {

    /**
     * 在服务端给定的候选集合内逐一判断文档关系。
     *
     * @param request 当前文档、候选文档、可引用分片和版本快照
     * @return 与候选集合一一对应的结构化关系判断
     */
    DocumentAssociationResult associate(DocumentAssociationRequest request);
}

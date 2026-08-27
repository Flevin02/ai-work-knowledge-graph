package com.flevin.knowgraph.server.service.documentgraph;

import com.flevin.knowgraph.server.model.documentgraph.DocumentGraphResponse;

/**
 * 独立文档关系图查询服务。
 */
public interface DocumentGraphService {

    /**
     * 查询当前知识空间的真实来源文档节点和已确认文档关系边。
     *
     * @param spaceId 知识空间标识
     * @return 文档关系图数据；没有关系时仍返回有效来源文档节点
     */
    DocumentGraphResponse getGraph(Long spaceId);
}

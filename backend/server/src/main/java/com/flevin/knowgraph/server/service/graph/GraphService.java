package com.flevin.knowgraph.server.service.graph;

import com.flevin.knowgraph.server.model.graph.GraphSummaryResponse;

/**
 * 知识图谱查询服务。
 */
public interface GraphService {

    /**
     * 获取当前知识图谱的节点、关系和审核数量摘要。
     *
     * @return 图谱摘要
     */
    GraphSummaryResponse getSummary();
}

package com.flevin.knowgraph.server.service.graph;

import com.flevin.knowgraph.server.model.graph.GraphSummaryResponse;
import com.flevin.knowgraph.server.model.graph.GraphDataResponse;

/**
 * 知识图谱查询服务。
 */
public interface GraphService {

    /**
     * 获取当前知识图谱的节点、关系和审核数量摘要。
     *
     * @param spaceId 知识空间标识
     * @return 图谱摘要
     */
    GraphSummaryResponse getSummary(Long spaceId);

    /**
     * 查询指定知识空间的图谱节点、关系和证据。
     *
     * @param spaceId 知识空间标识
     * @return 图谱基础数据
     */
    GraphDataResponse getGraph(Long spaceId);
}

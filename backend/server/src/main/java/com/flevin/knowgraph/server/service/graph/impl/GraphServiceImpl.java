package com.flevin.knowgraph.server.service.graph.impl;

import com.flevin.knowgraph.server.model.graph.GraphSummaryResponse;
import com.flevin.knowgraph.server.service.graph.GraphService;
import org.springframework.stereotype.Service;

/**
 * 知识图谱查询服务实现。
 */
@Service
public class GraphServiceImpl implements GraphService {

    /**
     * 获取当前知识图谱的节点、关系和审核数量摘要。
     *
     * @return 图谱摘要
     */
    @Override
    public GraphSummaryResponse getSummary() {
        return new GraphSummaryResponse(
                0,
                0,
                0,
                "图谱存储尚未初始化，当前接口用于联通性检查。"
        );
    }
}

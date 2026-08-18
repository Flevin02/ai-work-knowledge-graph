package com.flevin.knowgraph.server.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flevin.knowgraph.server.repository.entity.GraphEdgeEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 图谱关系 MyBatis-Plus Mapper。
 */
@Mapper
public interface GraphEdgeMapper extends BaseMapper<GraphEdgeEntity> {

    /**
     * 查询指定知识空间内未失效关系，并按创建顺序返回。
     *
     * @param spaceId 知识空间标识
     * @return 未失效关系
     */
    List<GraphEdgeEntity> findActiveBySpaceId(String spaceId);

    /**
     * 将连接到已失效节点的关系统一标记为 stale。
     *
     * @param spaceId 知识空间标识
     * @param nodeIds 已失效节点标识
     * @param updatedAt 更新时间
     * @return 实际更新关系数
     */
    int markStaleByNodeIds(
            @Param("spaceId") String spaceId,
            @Param("nodeIds") List<String> nodeIds,
            @Param("updatedAt") String updatedAt
    );

    /**
     * 将全部有效证据都已失效的关系标记为 stale。
     *
     * @param spaceId 知识空间标识
     * @param documentId 已删除来源资料标识
     * @param updatedAt 更新时间
     * @return 实际更新关系数
     */
    int markStaleWithoutActiveEvidence(
            @Param("spaceId") String spaceId,
            @Param("documentId") String documentId,
            @Param("updatedAt") String updatedAt
    );
}

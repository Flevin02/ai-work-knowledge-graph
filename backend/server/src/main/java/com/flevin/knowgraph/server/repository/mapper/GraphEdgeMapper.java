package com.flevin.knowgraph.server.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flevin.knowgraph.server.repository.entity.GraphEdgeEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

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
    List<GraphEdgeEntity> findActiveBySpaceId(Long spaceId);

    /**
     * 按主键查询关系，供候选物化和审核状态更新复用。
     *
     * @param edgeId 关系标识
     * @return 关系实体；不存在时为空
     */
    default Optional<GraphEdgeEntity> findById(Long edgeId) {
        return Optional.ofNullable(selectById(edgeId));
    }

    /**
     * 将连接到已失效节点的关系统一标记为 stale。
     *
     * @param spaceId 知识空间标识
     * @param nodeIds 已失效节点标识
     * @param updatedAt 更新时间
     * @return 实际更新关系数
     */
    int markStaleByNodeIds(
            @Param("spaceId") Long spaceId,
            @Param("nodeIds") List<Long> nodeIds,
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
            @Param("spaceId") Long spaceId,
            @Param("documentId") Long documentId,
            @Param("updatedAt") String updatedAt
    );
}

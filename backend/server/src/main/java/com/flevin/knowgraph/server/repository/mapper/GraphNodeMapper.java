package com.flevin.knowgraph.server.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flevin.knowgraph.server.repository.entity.GraphNodeEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 图谱节点 MyBatis-Plus Mapper。
 */
@Mapper
public interface GraphNodeMapper extends BaseMapper<GraphNodeEntity> {

    /**
     * 查询指定知识空间内未失效节点，并按创建顺序返回。
     *
     * @param spaceId 知识空间标识
     * @return 未失效节点
     */
    List<GraphNodeEntity> findActiveBySpaceId(Long spaceId);

    /**
     * 批量查询指定知识空间内可复用的规范化节点。
     *
     * @param spaceId 知识空间标识
     * @param normalizedKeys 规范化实体键
     * @return 已存在的节点
     */
    List<GraphNodeEntity> findBySpaceIdAndNormalizedKeys(
            @Param("spaceId") Long spaceId,
            @Param("normalizedKeys") List<String> normalizedKeys
    );
}

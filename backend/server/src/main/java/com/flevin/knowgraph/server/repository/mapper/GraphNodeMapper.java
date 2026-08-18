package com.flevin.knowgraph.server.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flevin.knowgraph.server.repository.entity.GraphNodeEntity;
import org.apache.ibatis.annotations.Mapper;

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
    List<GraphNodeEntity> findActiveBySpaceId(String spaceId);
}

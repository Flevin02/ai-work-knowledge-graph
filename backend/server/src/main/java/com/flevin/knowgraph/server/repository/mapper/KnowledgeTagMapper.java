package com.flevin.knowgraph.server.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flevin.knowgraph.server.repository.projection.KnowledgeTagSummaryProjection;
import com.flevin.knowgraph.server.repository.entity.KnowledgeTagEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 标签字典 MyBatis-Plus Mapper，负责空间内规范化标签的基础持久化操作。
 */
@Mapper
public interface KnowledgeTagMapper extends BaseMapper<KnowledgeTagEntity> {

    /**
     * 查询当前空间参与筛选的已确认标签和有效文档数量。
     *
     * @param spaceId 知识空间标识
     * @return 按文档数量、名称和标识稳定排序的标签摘要
     */
    List<KnowledgeTagSummaryProjection> findConfirmedSummaries(
            @Param("spaceId") String spaceId
    );
}

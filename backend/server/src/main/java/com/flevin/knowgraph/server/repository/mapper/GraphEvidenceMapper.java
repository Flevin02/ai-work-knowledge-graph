package com.flevin.knowgraph.server.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flevin.knowgraph.server.repository.entity.GraphEvidenceEntity;
import com.flevin.knowgraph.server.repository.entity.GraphEvidenceRow;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 图谱证据 MyBatis-Plus Mapper，保留证据与来源资料名称的必要联合查询。
 */
@Mapper
public interface GraphEvidenceMapper extends BaseMapper<GraphEvidenceEntity> {

    /**
     * 批量查询关系证据并联合来源资料名称，避免逐关系查询。
     *
     * @param spaceId 知识空间标识
     * @param edgeIds 关系标识列表
     * @return 带来源资料名称的证据行
     */
    List<GraphEvidenceRow> findRowsBySpaceIdAndEdgeIds(
            Long spaceId,
            List<Long> edgeIds
    );
}

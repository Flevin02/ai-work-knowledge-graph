package com.flevin.knowgraph.server.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flevin.knowgraph.server.repository.entity.ReviewActionEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 图谱关系审核动作 MyBatis-Plus Mapper。
 */
@Mapper
public interface ReviewActionMapper extends BaseMapper<ReviewActionEntity> {
}

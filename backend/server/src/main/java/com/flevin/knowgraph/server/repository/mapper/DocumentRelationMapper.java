package com.flevin.knowgraph.server.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flevin.knowgraph.server.repository.entity.DocumentRelationEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文档关系 MyBatis-Plus Mapper。
 */
@Mapper
public interface DocumentRelationMapper extends BaseMapper<DocumentRelationEntity> {
}

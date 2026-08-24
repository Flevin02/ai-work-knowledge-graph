package com.flevin.knowgraph.server.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flevin.knowgraph.server.repository.entity.DocumentRelationReviewEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文档关系审核历史 MyBatis-Plus Mapper。
 */
@Mapper
public interface DocumentRelationReviewMapper extends BaseMapper<DocumentRelationReviewEntity> {
}

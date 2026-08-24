package com.flevin.knowgraph.server.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flevin.knowgraph.server.repository.entity.DocumentTagReviewEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文档标签审核历史 MyBatis-Plus Mapper。
 */
@Mapper
public interface DocumentTagReviewMapper extends BaseMapper<DocumentTagReviewEntity> {
}

package com.flevin.knowgraph.server.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flevin.knowgraph.server.repository.entity.KnowledgeTagEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 标签字典 MyBatis-Plus Mapper，负责空间内规范化标签的基础持久化操作。
 */
@Mapper
public interface KnowledgeTagMapper extends BaseMapper<KnowledgeTagEntity> {
}

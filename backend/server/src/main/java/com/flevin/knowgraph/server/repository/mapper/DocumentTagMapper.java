package com.flevin.knowgraph.server.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flevin.knowgraph.server.repository.entity.DocumentTagEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文档标签关系 MyBatis-Plus Mapper，负责候选、确认、拒绝和失效状态的基础持久化操作。
 */
@Mapper
public interface DocumentTagMapper extends BaseMapper<DocumentTagEntity> {
}

package com.flevin.knowgraph.server.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flevin.knowgraph.server.repository.entity.ConversationEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 问答会话 MyBatis-Plus Mapper，负责会话的基础持久化操作。
 */
@Mapper
public interface ConversationMapper extends BaseMapper<ConversationEntity> {
}

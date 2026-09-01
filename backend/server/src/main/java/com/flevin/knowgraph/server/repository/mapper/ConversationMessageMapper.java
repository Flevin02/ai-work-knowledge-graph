package com.flevin.knowgraph.server.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flevin.knowgraph.server.repository.entity.ConversationMessageEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 问答消息 MyBatis-Plus Mapper，负责会话消息的基础持久化操作。
 */
@Mapper
public interface ConversationMessageMapper extends BaseMapper<ConversationMessageEntity> {
}

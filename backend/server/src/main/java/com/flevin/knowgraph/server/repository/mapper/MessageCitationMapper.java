package com.flevin.knowgraph.server.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flevin.knowgraph.server.repository.entity.MessageCitationEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 回答引用 MyBatis-Plus Mapper，负责回答引用的基础持久化操作。
 */
@Mapper
public interface MessageCitationMapper extends BaseMapper<MessageCitationEntity> {
}

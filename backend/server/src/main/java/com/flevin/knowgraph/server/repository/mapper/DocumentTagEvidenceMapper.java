package com.flevin.knowgraph.server.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flevin.knowgraph.server.repository.entity.DocumentTagEvidenceEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文档标签证据 MyBatis-Plus Mapper，负责逐字证据的基础持久化操作。
 */
@Mapper
public interface DocumentTagEvidenceMapper extends BaseMapper<DocumentTagEvidenceEntity> {
}

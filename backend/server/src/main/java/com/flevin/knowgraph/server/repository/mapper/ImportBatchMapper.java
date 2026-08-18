package com.flevin.knowgraph.server.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flevin.knowgraph.server.repository.entity.ImportBatchEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 导入批次 MyBatis-Plus Mapper。
 */
@Mapper
public interface ImportBatchMapper extends BaseMapper<ImportBatchEntity> {
}

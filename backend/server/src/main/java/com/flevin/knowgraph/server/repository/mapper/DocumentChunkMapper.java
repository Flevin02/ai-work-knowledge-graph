package com.flevin.knowgraph.server.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flevin.knowgraph.server.repository.entity.DocumentChunkEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 来源资料分片 MyBatis-Plus Mapper。
 */
@Mapper
public interface DocumentChunkMapper extends BaseMapper<DocumentChunkEntity> {

    /**
     * 按分片事实唯一键写入记录；并发重复分片时保留已经存在的事实标识。
     *
     * @param entity 待写入分片实体
     * @return 新增行数；事实已存在时为零
     */
    int insertIfAbsent(@Param("entity") DocumentChunkEntity entity);
}

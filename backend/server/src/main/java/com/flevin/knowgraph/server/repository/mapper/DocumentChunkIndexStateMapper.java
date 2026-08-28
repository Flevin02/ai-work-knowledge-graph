package com.flevin.knowgraph.server.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flevin.knowgraph.server.repository.entity.DocumentChunkIndexStateEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 分片向量索引状态 MyBatis-Plus Mapper。
 */
@Mapper
public interface DocumentChunkIndexStateMapper extends BaseMapper<DocumentChunkIndexStateEntity> {

    /**
     * 写入已校验向量事实；同一分片与模型边界重试时更新为最新就绪状态。
     *
     * @param entity 待写入或更新的向量索引状态实体
     * @return 新增或更新影响行数
     */
    int upsertReady(@Param("entity") DocumentChunkIndexStateEntity entity);
}

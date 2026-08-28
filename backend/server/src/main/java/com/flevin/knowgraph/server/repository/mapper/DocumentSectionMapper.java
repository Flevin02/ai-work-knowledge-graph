package com.flevin.knowgraph.server.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flevin.knowgraph.server.repository.entity.DocumentSectionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 来源资料章节 MyBatis-Plus Mapper。
 */
@Mapper
public interface DocumentSectionMapper extends BaseMapper<DocumentSectionEntity> {

    /**
     * 按章节事实唯一键写入记录；并发重复解析时保留已经存在的事实标识。
     *
     * @param entity 待写入章节实体
     * @return 新增行数；事实已存在时为零
     */
    int insertIfAbsent(@Param("entity") DocumentSectionEntity entity);
}

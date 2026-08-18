package com.flevin.knowgraph.server.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flevin.knowgraph.server.repository.entity.AiExtractionRunEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * AI 抽取运行 MyBatis-Plus Mapper。
 */
@Mapper
public interface AiExtractionRunMapper extends BaseMapper<AiExtractionRunEntity> {

    /**
     * 批量查询当前页来源资料的最近运行和最近成功抽取标识。
     *
     * @param spaceId 知识空间标识
     * @param documentIds 当前页来源资料标识
     * @return 每份存在抽取记录的资料对应一条最近运行
     */
    List<AiExtractionRunEntity> findLatestByDocumentIds(
            @Param("spaceId") String spaceId,
            @Param("documentIds") List<String> documentIds
    );
}

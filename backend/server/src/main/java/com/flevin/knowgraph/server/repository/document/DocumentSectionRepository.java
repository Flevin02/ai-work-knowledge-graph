package com.flevin.knowgraph.server.repository.document;

import com.flevin.knowgraph.server.model.ai.rag.DocumentSectionFact;
import com.flevin.knowgraph.server.repository.entity.DocumentSectionEntity;
import com.flevin.knowgraph.server.repository.mapper.DocumentSectionMapper;
import com.flevin.knowgraph.server.repository.mapping.DocumentSectionEntityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

import static com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery;

/**
 * 来源资料章节事实数据访问对象，负责按空间和来源资料读取可重建章节。
 */
@Repository
@RequiredArgsConstructor
public class DocumentSectionRepository {

    private final DocumentSectionMapper mapper;
    private final DocumentSectionEntityMapper entityMapper;

    /**
     * 保存一条章节事实。
     *
     * @param fact 章节领域事实
     */
    public void save(DocumentSectionFact fact) {
        // 将章节领域事实转换为 MyBatis-Plus 实体并写入事实表
        mapper.insertIfAbsent(entityMapper.toEntity(fact));
    }

    /**
     * 查询当前空间指定来源资料的章节，按原文顺序返回。
     *
     * @param spaceId 知识空间标识
     * @param sourceDocumentId 来源资料标识
     * @param parserVersion 章节解析规则版本
     * @return 章节事实列表
     */
    public List<DocumentSectionFact> findByDocument(
            Long spaceId,
            Long sourceDocumentId,
            String parserVersion
    ) {
        // 按空间、资料和解析版本查询章节，防止跨空间或跨版本读取原文事实
        List<DocumentSectionEntity> entities = mapper.selectList(
                lambdaQuery(DocumentSectionEntity.class)
                        .eq(DocumentSectionEntity::getSpaceId, spaceId)
                        .eq(DocumentSectionEntity::getSourceDocumentId, sourceDocumentId)
                        .eq(DocumentSectionEntity::getParserVersion, parserVersion)
                        .orderByAsc(DocumentSectionEntity::getOrdinal)
                        .orderByAsc(DocumentSectionEntity::getId)
        );

        // 将 ORM 实体转换为领域事实，避免上层感知 MyBatis-Plus
        return entities.stream().map(entityMapper::toDomain).toList();
    }
}

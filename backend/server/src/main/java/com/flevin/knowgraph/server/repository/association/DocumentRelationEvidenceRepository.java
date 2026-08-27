package com.flevin.knowgraph.server.repository.association;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.flevin.knowgraph.server.model.association.DocumentRelationEvidence;
import com.flevin.knowgraph.server.repository.entity.DocumentRelationEvidenceEntity;
import com.flevin.knowgraph.server.repository.mapper.DocumentRelationEvidenceMapper;
import com.flevin.knowgraph.server.repository.mapping.DocumentRelationEvidenceEntityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 文档关系证据数据访问对象，负责按关系读取和保存证据。
 */
@Repository
@RequiredArgsConstructor
public class DocumentRelationEvidenceRepository {

    private final DocumentRelationEvidenceMapper mapper;
    private final DocumentRelationEvidenceEntityMapper entityMapper;

    /**
     * 保存一条文档关系证据。
     *
     * @param evidence 文档关系证据领域模型
     */
    public void save(DocumentRelationEvidence evidence) {
        // 将领域证据转换为 MyBatis-Plus 实体并保存定位信息
        mapper.insert(entityMapper.toEntity(evidence));
    }

    /**
     * 查询指定空间和关系的全部证据。
     *
     * @param spaceId 知识空间标识
     * @param relationId 文档关系标识
     * @return 按创建时间和标识稳定排序的证据列表
     */
    public List<DocumentRelationEvidence> findAllByRelation(
            Long spaceId,
            Long relationId
    ) {
        // 按空间和关系边界查询，避免跨空间读取证据
        return mapper.selectList(
                        Wrappers.<DocumentRelationEvidenceEntity>lambdaQuery()
                                .eq(DocumentRelationEvidenceEntity::getSpaceId, spaceId)
                                .eq(DocumentRelationEvidenceEntity::getDocumentRelationId, relationId)
                                .orderByAsc(DocumentRelationEvidenceEntity::getCreatedAt)
                                .orderByAsc(DocumentRelationEvidenceEntity::getId)
                ).stream()
                .map(entityMapper::toDomain)
                .toList();
    }

    /**
     * 批量查询多条文档关系的全部证据。
     *
     * @param spaceId 知识空间标识
     * @param relationIds 文档关系标识集合
     * @return 按关系、创建时间和证据标识稳定排序的证据列表
     */
    public List<DocumentRelationEvidence> findAllByRelations(
            Long spaceId,
            List<Long> relationIds
    ) {
        if (relationIds.isEmpty()) {
            return List.of();
        }

        // 使用一次 IN 查询组装关系响应，避免逐关系读取证据形成 N+1
        return mapper.selectList(
                        Wrappers.<DocumentRelationEvidenceEntity>lambdaQuery()
                                .eq(DocumentRelationEvidenceEntity::getSpaceId, spaceId)
                                .in(DocumentRelationEvidenceEntity::getDocumentRelationId, relationIds)
                                .orderByAsc(DocumentRelationEvidenceEntity::getDocumentRelationId)
                                .orderByAsc(DocumentRelationEvidenceEntity::getCreatedAt)
                                .orderByAsc(DocumentRelationEvidenceEntity::getId)
                ).stream()
                .map(entityMapper::toDomain)
                .toList();
    }

}

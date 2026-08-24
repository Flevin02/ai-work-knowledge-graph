package com.flevin.knowgraph.server.repository.association;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.flevin.knowgraph.server.model.association.DocumentRelationEvidence;
import com.flevin.knowgraph.server.repository.entity.DocumentRelationEvidenceEntity;
import com.flevin.knowgraph.server.repository.mapper.DocumentRelationEvidenceMapper;
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

    /**
     * 保存一条文档关系证据。
     *
     * @param evidence 文档关系证据领域模型
     */
    public void save(DocumentRelationEvidence evidence) {
        // 将领域证据转换为 MyBatis-Plus 实体并保存定位信息
        mapper.insert(toEntity(evidence));
    }

    /**
     * 查询指定空间和关系的全部证据。
     *
     * @param spaceId 知识空间标识
     * @param relationId 文档关系标识
     * @return 按创建时间和标识稳定排序的证据列表
     */
    public List<DocumentRelationEvidence> findAllByRelation(
            String spaceId,
            String relationId
    ) {
        // 按空间和关系边界查询，避免跨空间读取证据
        return mapper.selectList(
                        Wrappers.<DocumentRelationEvidenceEntity>lambdaQuery()
                                .eq(DocumentRelationEvidenceEntity::getSpaceId, spaceId)
                                .eq(DocumentRelationEvidenceEntity::getDocumentRelationId, relationId)
                                .orderByAsc(DocumentRelationEvidenceEntity::getCreatedAt)
                                .orderByAsc(DocumentRelationEvidenceEntity::getId)
                ).stream()
                .map(this::toDomain)
                .toList();
    }

    /**
     * 将持久化实体转换为文档关系证据领域模型。
     *
     * @param entity MyBatis-Plus 持久化实体
     * @return 文档关系证据领域模型
     */
    private DocumentRelationEvidence toDomain(DocumentRelationEvidenceEntity entity) {
        return new DocumentRelationEvidence(
                entity.getId(),
                entity.getSpaceId(),
                entity.getDocumentRelationId(),
                entity.getSourceDocumentId(),
                entity.getChunkId(),
                entity.getSectionPath(),
                entity.getQuote(),
                entity.getStartOffset(),
                entity.getEndOffset(),
                entity.getEvidenceRole(),
                java.time.Instant.parse(entity.getCreatedAt())
        );
    }

    /**
     * 将文档关系证据领域模型转换为 MyBatis-Plus 实体。
     *
     * @param evidence 文档关系证据领域模型
     * @return MyBatis-Plus 持久化实体
     */
    private DocumentRelationEvidenceEntity toEntity(DocumentRelationEvidence evidence) {
        DocumentRelationEvidenceEntity entity = new DocumentRelationEvidenceEntity();
        entity.setId(evidence.id());
        entity.setSpaceId(evidence.spaceId());
        entity.setDocumentRelationId(evidence.documentRelationId());
        entity.setSourceDocumentId(evidence.sourceDocumentId());
        entity.setChunkId(evidence.chunkId());
        entity.setSectionPath(evidence.sectionPath());
        entity.setQuote(evidence.quote());
        entity.setStartOffset(evidence.startOffset());
        entity.setEndOffset(evidence.endOffset());
        entity.setEvidenceRole(evidence.evidenceRole());
        entity.setCreatedAt(evidence.createdAt().toString());
        return entity;
    }
}

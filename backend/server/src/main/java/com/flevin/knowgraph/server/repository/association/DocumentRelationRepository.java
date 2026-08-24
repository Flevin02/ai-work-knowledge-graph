package com.flevin.knowgraph.server.repository.association;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.flevin.knowgraph.server.model.association.DocumentRelation;
import com.flevin.knowgraph.server.repository.entity.DocumentRelationEntity;
import com.flevin.knowgraph.server.repository.mapper.DocumentRelationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 文档关系数据访问对象，负责稳定关系键查询、保存和状态更新。
 */
@Repository
@RequiredArgsConstructor
public class DocumentRelationRepository {

    private final DocumentRelationMapper mapper;

    /**
     * 按知识空间和稳定关系键查询关系，支撑关联运行幂等。
     *
     * @param spaceId 知识空间标识
     * @param relationKey 稳定关系键
     * @return 已存在关系；不存在时返回空
     */
    public Optional<DocumentRelation> findByRelationKey(
            String spaceId,
            String relationKey
    ) {
        // 只在当前知识空间内读取关系，避免相同关系键跨空间复用
        DocumentRelationEntity entity = mapper.selectOne(
                Wrappers.<DocumentRelationEntity>lambdaQuery()
                        .eq(DocumentRelationEntity::getSpaceId, spaceId)
                        .eq(DocumentRelationEntity::getRelationKey, relationKey)
        );
        return Optional.ofNullable(entity).map(this::toDomain);
    }

    /**
     * 按空间和关系标识查询文档关系。
     *
     * @param spaceId 知识空间标识
     * @param relationId 文档关系标识
     * @return 文档关系；不存在时返回空
     */
    public Optional<DocumentRelation> findById(
            String spaceId,
            String relationId
    ) {
        // 使用空间和主键双重边界查询关系
        DocumentRelationEntity entity = mapper.selectOne(
                Wrappers.<DocumentRelationEntity>lambdaQuery()
                        .eq(DocumentRelationEntity::getSpaceId, spaceId)
                        .eq(DocumentRelationEntity::getId, relationId)
        );
        return Optional.ofNullable(entity).map(this::toDomain);
    }

    /**
     * 查询一次关联运行新保存的全部文档关系。
     *
     * @param spaceId 知识空间标识
     * @param runId 文档关联运行标识
     * @return 按创建时间和关系标识稳定排序的关系列表
     */
    public List<DocumentRelation> findAllByRun(
            String spaceId,
            String runId
    ) {
        // 运行详情只读取当前空间内由该运行新保存的关系
        return mapper.selectList(
                        Wrappers.<DocumentRelationEntity>lambdaQuery()
                                .eq(DocumentRelationEntity::getSpaceId, spaceId)
                                .eq(DocumentRelationEntity::getAssociationRunId, runId)
                                .orderByAsc(DocumentRelationEntity::getCreatedAt)
                                .orderByAsc(DocumentRelationEntity::getId)
                ).stream()
                .map(this::toDomain)
                .toList();
    }

    /**
     * 查询一份来源资料作为任一关系端点的全部文档关系。
     *
     * @param spaceId 知识空间标识
     * @param documentId 来源资料标识
     * @return 当前资料相关的关系列表，最近更新优先
     */
    public List<DocumentRelation> findAllByDocument(
            String spaceId,
            String documentId
    ) {
        // 同时匹配有向关系和规范化后的对称关系两端
        return mapper.selectList(
                        Wrappers.<DocumentRelationEntity>lambdaQuery()
                                .eq(DocumentRelationEntity::getSpaceId, spaceId)
                                .and(wrapper -> wrapper
                                        .eq(DocumentRelationEntity::getSourceDocumentId, documentId)
                                        .or()
                                        .eq(DocumentRelationEntity::getTargetDocumentId, documentId))
                                .orderByDesc(DocumentRelationEntity::getUpdatedAt)
                                .orderByDesc(DocumentRelationEntity::getId)
                ).stream()
                .map(this::toDomain)
                .toList();
    }

    /**
     * 保存一条文档关系。
     *
     * @param relation 文档关系领域模型
     */
    public void save(DocumentRelation relation) {
        // 将领域关系转换为 MyBatis-Plus 实体并保存候选关系
        mapper.insert(toEntity(relation));
    }

    /**
     * 更新文档关系审核状态和更新时间。
     *
     * @param spaceId 知识空间标识
     * @param relationId 文档关系标识
     * @param status 新状态
     * @param updatedAt 更新时间
     * @return 实际更新记录数
     */
    public int updateStatus(
            String spaceId,
            String relationId,
            String status,
            Instant updatedAt
    ) {
        DocumentRelationEntity entity = new DocumentRelationEntity();
        entity.setStatus(status);
        entity.setUpdatedAt(updatedAt.toString());

        // 只按空间和关系标识更新状态，保留关系主体、证据和版本快照
        return mapper.update(
                entity,
                Wrappers.<DocumentRelationEntity>lambdaUpdate()
                        .eq(DocumentRelationEntity::getSpaceId, spaceId)
                        .eq(DocumentRelationEntity::getId, relationId)
        );
    }

    /**
     * 将持久化实体转换为文档关系领域模型。
     *
     * @param entity MyBatis-Plus 持久化实体
     * @return 文档关系领域模型
     */
    private DocumentRelation toDomain(DocumentRelationEntity entity) {
        return new DocumentRelation(
                entity.getId(),
                entity.getSpaceId(),
                entity.getSourceDocumentId(),
                entity.getTargetDocumentId(),
                entity.getRelationType(),
                entity.getDirection(),
                entity.getStatus(),
                entity.getGenerationMode(),
                entity.getConfidence(),
                entity.getReason(),
                entity.getAssociationRunId(),
                entity.getSourceContentHash(),
                entity.getTargetContentHash(),
                entity.getAssociationPolicyVersion(),
                entity.getRelationKey(),
                Instant.parse(entity.getCreatedAt()),
                Instant.parse(entity.getUpdatedAt())
        );
    }

    /**
     * 将文档关系领域模型转换为 MyBatis-Plus 实体。
     *
     * @param relation 文档关系领域模型
     * @return MyBatis-Plus 持久化实体
     */
    private DocumentRelationEntity toEntity(DocumentRelation relation) {
        DocumentRelationEntity entity = new DocumentRelationEntity();
        entity.setId(relation.id());
        entity.setSpaceId(relation.spaceId());
        entity.setSourceDocumentId(relation.sourceDocumentId());
        entity.setTargetDocumentId(relation.targetDocumentId());
        entity.setRelationType(relation.relationType());
        entity.setDirection(relation.direction());
        entity.setStatus(relation.status());
        entity.setGenerationMode(relation.generationMode());
        entity.setConfidence(relation.confidence());
        entity.setReason(relation.reason());
        entity.setAssociationRunId(relation.associationRunId());
        entity.setSourceContentHash(relation.sourceContentHash());
        entity.setTargetContentHash(relation.targetContentHash());
        entity.setAssociationPolicyVersion(relation.associationPolicyVersion());
        entity.setRelationKey(relation.relationKey());
        entity.setCreatedAt(relation.createdAt().toString());
        entity.setUpdatedAt(relation.updatedAt().toString());
        return entity;
    }
}

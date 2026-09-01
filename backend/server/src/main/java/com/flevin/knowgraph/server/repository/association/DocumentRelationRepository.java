package com.flevin.knowgraph.server.repository.association;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.flevin.knowgraph.server.model.association.DocumentRelation;
import com.flevin.knowgraph.server.repository.entity.DocumentRelationEntity;
import com.flevin.knowgraph.server.repository.mapper.DocumentRelationMapper;
import com.flevin.knowgraph.server.repository.mapping.DocumentRelationEntityMapper;
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
    private final DocumentRelationEntityMapper entityMapper;

    /**
     * 按知识空间和稳定关系键查询关系，支撑关联运行幂等。
     *
     * @param spaceId 知识空间标识
     * @param relationKey 稳定关系键
     * @return 已存在关系；不存在时返回空
     */
    public Optional<DocumentRelation> findByRelationKey(
            Long spaceId,
            String relationKey
    ) {
        // 只在当前知识空间内读取关系，避免相同关系键跨空间复用
        DocumentRelationEntity entity = mapper.selectOne(
                Wrappers.<DocumentRelationEntity>lambdaQuery()
                        .eq(DocumentRelationEntity::getSpaceId, spaceId)
                        .eq(DocumentRelationEntity::getRelationKey, relationKey)
        );
        return Optional.ofNullable(entity).map(entityMapper::toDomain);
    }

    /**
     * 按空间和关系标识查询文档关系。
     *
     * @param spaceId 知识空间标识
     * @param relationId 文档关系标识
     * @return 文档关系；不存在时返回空
     */
    public Optional<DocumentRelation> findById(
            Long spaceId,
            Long relationId
    ) {
        // 使用空间和主键双重边界查询关系
        DocumentRelationEntity entity = mapper.selectOne(
                Wrappers.<DocumentRelationEntity>lambdaQuery()
                        .eq(DocumentRelationEntity::getSpaceId, spaceId)
                        .eq(DocumentRelationEntity::getId, relationId)
        );
        return Optional.ofNullable(entity).map(entityMapper::toDomain);
    }

    /**
     * 查询一次关联运行新保存的全部文档关系。
     *
     * @param spaceId 知识空间标识
     * @param runId 文档关联运行标识
     * @return 按创建时间和关系标识稳定排序的关系列表
     */
    public List<DocumentRelation> findAllByRun(
            Long spaceId,
            Long runId
    ) {
        // 运行详情只读取当前空间内由该运行新保存的关系
        return mapper.selectList(
                        Wrappers.<DocumentRelationEntity>lambdaQuery()
                                .eq(DocumentRelationEntity::getSpaceId, spaceId)
                                .eq(DocumentRelationEntity::getAssociationRunId, runId)
                                .orderByAsc(DocumentRelationEntity::getCreatedAt)
                                .orderByAsc(DocumentRelationEntity::getId)
                ).stream()
                .map(entityMapper::toDomain)
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
            Long spaceId,
            Long documentId
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
                .map(entityMapper::toDomain)
                .toList();
    }

    /**
     * 按空间和审核状态查询文档关系，供独立文档关系图批量组装使用。
     *
     * @param spaceId 知识空间标识
     * @param status 关系审核状态
     * @return 按最近更新时间倒序排列的文档关系
     */
    public List<DocumentRelation> findAllBySpaceAndStatus(
            Long spaceId,
            String status
    ) {
        // 只在当前知识空间读取目标状态关系，避免跨空间构造图边
        return mapper.selectList(
                        Wrappers.<DocumentRelationEntity>lambdaQuery()
                                .eq(DocumentRelationEntity::getSpaceId, spaceId)
                                .eq(DocumentRelationEntity::getStatus, status)
                                .orderByDesc(DocumentRelationEntity::getUpdatedAt)
                                .orderByDesc(DocumentRelationEntity::getId)
                ).stream()
                .map(entityMapper::toDomain)
                .toList();
    }

    /**
     * 保存一条文档关系。
     *
     * @param relation 文档关系领域模型
     */
    public void save(DocumentRelation relation) {
        // 将领域关系转换为 MyBatis-Plus 实体并保存候选关系
        mapper.insert(entityMapper.toEntity(relation));
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
            Long spaceId,
            Long relationId,
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
     * 将一份来源资料作为任意端点的 suggested 和 confirmed 关系标记为 stale。
     *
     * <p>用于来源资料内容更新后冻结基于旧内容哈希的关系事实；
     * rejected 与已 stale 的关系保持原状态。</p>
     *
     * @param spaceId 知识空间标识
     * @param documentId 内容发生变化的来源资料标识
     * @param updatedAt 失效时间
     * @return 实际标记为 stale 的行数
     */
    public int markStaleByDocumentEndpoint(
            Long spaceId,
            Long documentId,
            Instant updatedAt
    ) {
        DocumentRelationEntity entity = new DocumentRelationEntity();
        entity.setStatus("stale");
        entity.setUpdatedAt(updatedAt.toString());

        // 关系两端任一端命中即失效，同时限定当前态为 suggested 或 confirmed
        return mapper.update(
                entity,
                Wrappers.<DocumentRelationEntity>lambdaUpdate()
                        .eq(DocumentRelationEntity::getSpaceId, spaceId)
                        .and(wrapper -> wrapper
                                .eq(DocumentRelationEntity::getSourceDocumentId, documentId)
                                .or()
                                .eq(DocumentRelationEntity::getTargetDocumentId, documentId))
                        .in(DocumentRelationEntity::getStatus, "suggested", "confirmed")
        );
    }

}

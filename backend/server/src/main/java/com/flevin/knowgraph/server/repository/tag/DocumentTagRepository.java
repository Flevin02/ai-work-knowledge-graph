package com.flevin.knowgraph.server.repository.tag;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.flevin.knowgraph.server.model.tag.DocumentTag;
import com.flevin.knowgraph.server.model.tag.DocumentTagEvidence;
import com.flevin.knowgraph.server.model.tag.KnowledgeTag;
import com.flevin.knowgraph.server.repository.entity.DocumentTagEntity;
import com.flevin.knowgraph.server.repository.entity.DocumentTagEvidenceEntity;
import com.flevin.knowgraph.server.repository.entity.KnowledgeTagEntity;
import com.flevin.knowgraph.server.repository.mapper.DocumentTagEvidenceMapper;
import com.flevin.knowgraph.server.repository.mapper.DocumentTagMapper;
import com.flevin.knowgraph.server.repository.mapper.KnowledgeTagMapper;
import com.flevin.knowgraph.server.repository.projection.KnowledgeTagSummaryProjection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 文档标签聚合数据访问对象，统一维护标签定义、文档标签关系和逐字证据。
 */
@Repository
@RequiredArgsConstructor
public class DocumentTagRepository {

    private final KnowledgeTagMapper knowledgeTagMapper;
    private final DocumentTagMapper documentTagMapper;
    private final DocumentTagEvidenceMapper evidenceMapper;

    /**
     * 按空间和规范化键查询可复用的有效标签定义。
     *
     * @param spaceId 知识空间标识
     * @param normalizedKey 标签规范化键
     * @return 有效标签定义；不存在时返回空
     */
    public Optional<KnowledgeTag> findTagByNormalizedKey(
            Long spaceId,
            String normalizedKey
    ) {
        // 限定知识空间、规范化键和有效状态查询唯一标签定义
        KnowledgeTagEntity entity = knowledgeTagMapper.selectOne(
                Wrappers.<KnowledgeTagEntity>lambdaQuery()
                        .eq(KnowledgeTagEntity::getSpaceId, spaceId)
                        .eq(KnowledgeTagEntity::getNormalizedKey, normalizedKey)
                        .eq(KnowledgeTagEntity::getStatus, "active")
        );

        // 将持久化实体转换为领域模型，避免 ORM 类型泄漏到 Service
        return Optional.ofNullable(entity).map(this::toDomain);
    }

    /**
     * 按空间和标识查询标签定义。
     *
     * @param spaceId 知识空间标识
     * @param tagId 标签标识
     * @return 标签定义；不存在时返回空
     */
    public Optional<KnowledgeTag> findTagById(
            Long spaceId,
            Long tagId
    ) {
        // 限定知识空间和主键查询标签，阻断跨空间读取
        KnowledgeTagEntity entity = knowledgeTagMapper.selectOne(
                Wrappers.<KnowledgeTagEntity>lambdaQuery()
                        .eq(KnowledgeTagEntity::getSpaceId, spaceId)
                        .eq(KnowledgeTagEntity::getId, tagId)
        );

        // 将持久化实体转换为领域模型
        return Optional.ofNullable(entity).map(this::toDomain);
    }

    /**
     * 保存新的空间内标签定义。
     *
     * @param tag 已完成名称规范化的标签定义
     */
    public void saveTag(KnowledgeTag tag) {
        // 将领域标签转换为 MyBatis-Plus 实体并写入标签字典
        knowledgeTagMapper.insert(toEntity(tag));
    }

    /**
     * 按空间和稳定幂等键查询文档标签关系。
     *
     * @param spaceId 知识空间标识
     * @param documentTagKey 文档标签稳定幂等键
     * @return 已存在的文档标签关系；不存在时返回空
     */
    public Optional<DocumentTag> findDocumentTagByKey(
            Long spaceId,
            String documentTagKey
    ) {
        // 使用空间和稳定键查询重复运行已经物化的文档标签
        DocumentTagEntity entity = documentTagMapper.selectOne(
                Wrappers.<DocumentTagEntity>lambdaQuery()
                        .eq(DocumentTagEntity::getSpaceId, spaceId)
                        .eq(DocumentTagEntity::getDocumentTagKey, documentTagKey)
        );

        // 将持久化实体转换为领域模型
        return Optional.ofNullable(entity).map(this::toDomain);
    }

    /**
     * 按空间和标识查询文档标签关系。
     *
     * @param spaceId 知识空间标识
     * @param documentTagId 文档标签关系标识
     * @return 文档标签关系；不存在时返回空
     */
    public Optional<DocumentTag> findDocumentTagById(
            Long spaceId,
            Long documentTagId
    ) {
        // 限定知识空间和主键查询文档标签关系
        DocumentTagEntity entity = documentTagMapper.selectOne(
                Wrappers.<DocumentTagEntity>lambdaQuery()
                        .eq(DocumentTagEntity::getSpaceId, spaceId)
                        .eq(DocumentTagEntity::getId, documentTagId)
        );

        // 将持久化实体转换为领域模型
        return Optional.ofNullable(entity).map(this::toDomain);
    }

    /**
     * 查询一份来源资料的全部文档标签关系。
     *
     * @param spaceId 知识空间标识
     * @param sourceDocumentId 来源资料标识
     * @return 按更新时间和标识倒序排列的文档标签关系
     */
    public List<DocumentTag> findAllByDocument(
            Long spaceId,
            Long sourceDocumentId
    ) {
        // 按空间和来源资料批量读取全部状态，供后续审核恢复使用
        return documentTagMapper.selectList(
                        Wrappers.<DocumentTagEntity>lambdaQuery()
                                .eq(DocumentTagEntity::getSpaceId, spaceId)
                                .eq(DocumentTagEntity::getSourceDocumentId, sourceDocumentId)
                                .orderByDesc(DocumentTagEntity::getUpdatedAt)
                                .orderByDesc(DocumentTagEntity::getId)
                ).stream()
                .map(this::toDomain)
                .toList();
    }

    /**
     * 按空间和来源资料批量读取已确认标签名称，供可选候选召回使用。
     *
     * @param spaceId 知识空间标识
     * @param sourceDocumentIds 来源资料标识列表
     * @return 来源资料标识到已确认标签展示名的映射
     */
    public Map<Long, List<String>> findConfirmedTagNamesByDocuments(
            Long spaceId,
            List<Long> sourceDocumentIds
    ) {
        if (sourceDocumentIds.isEmpty()) {
            return Map.of();
        }

        // 一次读取所有文档的 confirmed 关系，避免候选循环中逐文档查询标签
        List<DocumentTagEntity> relations = documentTagMapper.selectList(
                Wrappers.<DocumentTagEntity>lambdaQuery()
                        .eq(DocumentTagEntity::getSpaceId, spaceId)
                        .in(DocumentTagEntity::getSourceDocumentId, sourceDocumentIds)
                        .eq(DocumentTagEntity::getStatus, "confirmed")
        );
        List<Long> tagIds = relations.stream()
                .map(DocumentTagEntity::getTagId)
                .distinct()
                .toList();
        Map<Long, String> tagNamesById = findTagsByIds(spaceId, tagIds).stream()
                .collect(java.util.stream.Collectors.toMap(
                        KnowledgeTag::id,
                        KnowledgeTag::name,
                        (left, right) -> left
                ));
        Map<Long, List<String>> namesByDocument = new LinkedHashMap<>();
        relations.forEach(relation -> {
            String tagName = tagNamesById.get(relation.getTagId());
            if (tagName != null && !tagName.isBlank()) {
                namesByDocument.computeIfAbsent(
                        relation.getSourceDocumentId(),
                        ignored -> new ArrayList<>())
                        .add(tagName);
            }
        });
        return namesByDocument.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> List.copyOf(entry.getValue())
                ));
    }

    /**
     * 查询一次标签运行实际新保存的文档标签关系。
     *
     * @param spaceId 知识空间标识
     * @param runId 标签运行标识
     * @return 按创建时间和标识排序的文档标签关系
     */
    public List<DocumentTag> findAllByExtractionRun(
            Long spaceId,
            Long runId
    ) {
        // 按空间和运行标识批量读取本次新保存候选，幂等复用的旧候选不属于新运行
        return documentTagMapper.selectList(
                        Wrappers.<DocumentTagEntity>lambdaQuery()
                                .eq(DocumentTagEntity::getSpaceId, spaceId)
                                .eq(DocumentTagEntity::getExtractionRunId, runId)
                                .orderByAsc(DocumentTagEntity::getCreatedAt)
                                .orderByAsc(DocumentTagEntity::getId)
                ).stream()
                .map(this::toDomain)
                .toList();
    }

    /**
     * 查询当前空间内拥有指定 confirmed 标签的来源资料标识。
     *
     * @param spaceId 知识空间标识
     * @param tagId 标签定义标识
     * @return 含该 confirmed 标签的来源资料标识列表
     */
    public List<Long> findConfirmedDocumentIdsByTag(
            Long spaceId,
            Long tagId
    ) {
        // 只读取 confirmed 关系，与文档关系图只展示已确认事实的口径一致
        return documentTagMapper.selectList(
                        Wrappers.<DocumentTagEntity>lambdaQuery()
                                .eq(DocumentTagEntity::getSpaceId, spaceId)
                                .eq(DocumentTagEntity::getTagId, tagId)
                                .eq(DocumentTagEntity::getStatus, "confirmed")
                ).stream()
                .map(DocumentTagEntity::getSourceDocumentId)
                .distinct()
                .toList();
    }

    /**
     * 批量查询当前空间内的标签定义。
     *
     * @param spaceId 知识空间标识
     * @param tagIds 标签标识列表
     * @return 标签定义列表
     */
    public List<KnowledgeTag> findTagsByIds(
            Long spaceId,
            List<Long> tagIds
    ) {        if (tagIds.isEmpty()) {
            return List.of();
        }

        // 使用空间和标签标识集合一次读取全部标签定义，避免响应组装产生 N+1
        return knowledgeTagMapper.selectList(
                        Wrappers.<KnowledgeTagEntity>lambdaQuery()
                                .eq(KnowledgeTagEntity::getSpaceId, spaceId)
                                .in(KnowledgeTagEntity::getId, tagIds)
                ).stream()
                .map(this::toDomain)
                .toList();
    }

    /**
     * 查询当前空间参与用户筛选的已确认标签和有效文档数量。
     *
     * @param spaceId 知识空间标识
     * @return 已确认标签统计投影
     */
    public List<KnowledgeTagSummaryProjection> findConfirmedSummaries(Long spaceId) {
        // 使用自定义 Join 聚合一次计算标签下的有效确认文档数
        return knowledgeTagMapper.findConfirmedSummaries(spaceId);
    }

    /**
     * 保存文档标签关系。
     *
     * @param documentTag 已通过领域校验的文档标签关系
     */
    public void saveDocumentTag(DocumentTag documentTag) {
        // 将领域关系转换为 MyBatis-Plus 实体并保存
        documentTagMapper.insert(toEntity(documentTag));
    }

    /**
     * 仅将 suggested 文档标签迁移为审核后的最终状态。
     *
     * @param spaceId 知识空间标识
     * @param documentTagId 文档标签关系标识
     * @param nextStatus 目标状态：confirmed 或 rejected
     * @param updatedAt 审核更新时间
     * @return 实际更新行数；并发或重复审核时为 0
     */
    public int updateSuggestedStatus(
            Long spaceId,
            Long documentTagId,
            String nextStatus,
            Instant updatedAt
    ) {
        DocumentTagEntity updateEntity = new DocumentTagEntity();
        updateEntity.setStatus(nextStatus);
        updateEntity.setUpdatedAt(updatedAt.toString());

        // 同时限定空间、标识和 suggested 当前态，防止并发或重复审核覆盖历史结果
        LambdaUpdateWrapper<DocumentTagEntity> updateWrapper = Wrappers.lambdaUpdate();
        updateWrapper.eq(DocumentTagEntity::getSpaceId, spaceId)
                .eq(DocumentTagEntity::getId, documentTagId)
                .eq(DocumentTagEntity::getStatus, "suggested");

        // 使用 MyBatis-Plus 条件更新执行原子状态迁移
        return documentTagMapper.update(updateEntity, updateWrapper);
    }

    /**
     * 保存一条已逐字校验的标签证据。
     *
     * @param evidence 文档标签证据
     */
    public void saveEvidence(DocumentTagEvidence evidence) {
        // 将领域证据转换为 MyBatis-Plus 实体并保存
        evidenceMapper.insert(toEntity(evidence));
    }

    /**
     * 查询一条文档标签关系的全部证据。
     *
     * @param spaceId 知识空间标识
     * @param documentTagId 文档标签关系标识
     * @return 按创建时间和标识排序的证据列表
     */
    public List<DocumentTagEvidence> findEvidenceByDocumentTag(
            Long spaceId,
            Long documentTagId
    ) {
        // 限定空间和文档标签关系批量读取证据
        return evidenceMapper.selectList(
                        Wrappers.<DocumentTagEvidenceEntity>lambdaQuery()
                                .eq(DocumentTagEvidenceEntity::getSpaceId, spaceId)
                                .eq(DocumentTagEvidenceEntity::getDocumentTagId, documentTagId)
                                .orderByAsc(DocumentTagEvidenceEntity::getCreatedAt)
                                .orderByAsc(DocumentTagEvidenceEntity::getId)
                ).stream()
                .map(this::toDomain)
                .toList();
    }

    /**
     * 批量查询多条文档标签关系的全部逐字证据。
     *
     * @param spaceId 知识空间标识
     * @param documentTagIds 文档标签关系标识列表
     * @return 按关系、创建时间和标识排序的证据列表
     */
    public List<DocumentTagEvidence> findEvidenceByDocumentTags(
            Long spaceId,
            List<Long> documentTagIds
    ) {
        if (documentTagIds.isEmpty()) {
            return List.of();
        }

        // 使用文档标签标识集合一次读取全部证据，供运行恢复批量组装
        return evidenceMapper.selectList(
                        Wrappers.<DocumentTagEvidenceEntity>lambdaQuery()
                                .eq(DocumentTagEvidenceEntity::getSpaceId, spaceId)
                                .in(DocumentTagEvidenceEntity::getDocumentTagId, documentTagIds)
                                .orderByAsc(DocumentTagEvidenceEntity::getDocumentTagId)
                                .orderByAsc(DocumentTagEvidenceEntity::getCreatedAt)
                                .orderByAsc(DocumentTagEvidenceEntity::getId)
                ).stream()
                .map(this::toDomain)
                .toList();
    }

    /**
     * 将标签持久化实体转换为领域模型。
     *
     * @param entity 标签持久化实体
     * @return 标签领域模型
     */
    private KnowledgeTag toDomain(KnowledgeTagEntity entity) {
        return new KnowledgeTag(
                entity.getId(),
                entity.getSpaceId(),
                entity.getName(),
                entity.getNormalizedKey(),
                entity.getStatus(),
                Instant.parse(entity.getCreatedAt()),
                Instant.parse(entity.getUpdatedAt())
        );
    }

    /**
     * 将文档标签持久化实体转换为领域模型。
     *
     * @param entity 文档标签持久化实体
     * @return 文档标签领域模型
     */
    private DocumentTag toDomain(DocumentTagEntity entity) {
        return new DocumentTag(
                entity.getId(),
                entity.getSpaceId(),
                entity.getSourceDocumentId(),
                entity.getTagId(),
                entity.getSourceType(),
                entity.getStatus(),
                entity.getConfidence(),
                entity.getExtractionRunId(),
                entity.getContentHash(),
                entity.getPromptVersion(),
                entity.getSchemaVersion(),
                entity.getDocumentTagKey(),
                Instant.parse(entity.getCreatedAt()),
                Instant.parse(entity.getUpdatedAt())
        );
    }

    /**
     * 将标签证据持久化实体转换为领域模型。
     *
     * @param entity 标签证据持久化实体
     * @return 标签证据领域模型
     */
    private DocumentTagEvidence toDomain(DocumentTagEvidenceEntity entity) {
        return new DocumentTagEvidence(
                entity.getId(),
                entity.getSpaceId(),
                entity.getDocumentTagId(),
                entity.getSourceDocumentId(),
                entity.getChunkId(),
                entity.getSectionPath(),
                entity.getQuote(),
                entity.getStartOffset(),
                entity.getEndOffset(),
                Instant.parse(entity.getCreatedAt())
        );
    }

    /**
     * 将标签领域模型转换为持久化实体。
     *
     * @param tag 标签领域模型
     * @return 标签持久化实体
     */
    /**
     * 将一份来源资料下 suggested 和 confirmed 标签统一标记为 stale。
     *
     * <p>用于来源资料内容更新后冻结基于旧内容的事实：rejected 与已 stale 的
     * 关系保持原状态，证据和审核历史继续保留用于追溯。</p>
     *
     * @param spaceId 知识空间标识
     * @param sourceDocumentId 来源资料标识
     * @param updatedAt 失效时间
     * @return 实际标记为 stale 的行数
     */
    public int markStaleByDocument(
            Long spaceId,
            Long sourceDocumentId,
            Instant updatedAt
    ) {
        DocumentTagEntity updateEntity = new DocumentTagEntity();
        updateEntity.setStatus("stale");
        updateEntity.setUpdatedAt(updatedAt.toString());

        // 只迁移 suggested 与 confirmed，rejected 与 stale 保持不变
        LambdaUpdateWrapper<DocumentTagEntity> updateWrapper = Wrappers.lambdaUpdate();
        updateWrapper.eq(DocumentTagEntity::getSpaceId, spaceId)
                .eq(DocumentTagEntity::getSourceDocumentId, sourceDocumentId)
                .in(DocumentTagEntity::getStatus, "suggested", "confirmed");

        return documentTagMapper.update(updateEntity, updateWrapper);
    }

    private KnowledgeTagEntity toEntity(KnowledgeTag tag) {
        KnowledgeTagEntity entity = new KnowledgeTagEntity();
        entity.setId(tag.id());
        entity.setSpaceId(tag.spaceId());
        entity.setName(tag.name());
        entity.setNormalizedKey(tag.normalizedKey());
        entity.setStatus(tag.status());
        entity.setCreatedAt(tag.createdAt().toString());
        entity.setUpdatedAt(tag.updatedAt().toString());
        return entity;
    }

    /**
     * 将文档标签领域模型转换为持久化实体。
     *
     * @param documentTag 文档标签领域模型
     * @return 文档标签持久化实体
     */
    private DocumentTagEntity toEntity(DocumentTag documentTag) {
        DocumentTagEntity entity = new DocumentTagEntity();
        entity.setId(documentTag.id());
        entity.setSpaceId(documentTag.spaceId());
        entity.setSourceDocumentId(documentTag.sourceDocumentId());
        entity.setTagId(documentTag.tagId());
        entity.setSourceType(documentTag.sourceType());
        entity.setStatus(documentTag.status());
        entity.setConfidence(documentTag.confidence());
        entity.setExtractionRunId(documentTag.extractionRunId());
        entity.setContentHash(documentTag.contentHash());
        entity.setPromptVersion(documentTag.promptVersion());
        entity.setSchemaVersion(documentTag.schemaVersion());
        entity.setDocumentTagKey(documentTag.documentTagKey());
        entity.setCreatedAt(documentTag.createdAt().toString());
        entity.setUpdatedAt(documentTag.updatedAt().toString());
        return entity;
    }

    /**
     * 将标签证据领域模型转换为持久化实体。
     *
     * @param evidence 标签证据领域模型
     * @return 标签证据持久化实体
     */
    private DocumentTagEvidenceEntity toEntity(DocumentTagEvidence evidence) {
        DocumentTagEvidenceEntity entity = new DocumentTagEvidenceEntity();
        entity.setId(evidence.id());
        entity.setSpaceId(evidence.spaceId());
        entity.setDocumentTagId(evidence.documentTagId());
        entity.setSourceDocumentId(evidence.sourceDocumentId());
        entity.setChunkId(evidence.chunkId());
        entity.setSectionPath(evidence.sectionPath());
        entity.setQuote(evidence.quote());
        entity.setStartOffset(evidence.startOffset());
        entity.setEndOffset(evidence.endOffset());
        entity.setCreatedAt(evidence.createdAt().toString());
        return entity;
    }
}

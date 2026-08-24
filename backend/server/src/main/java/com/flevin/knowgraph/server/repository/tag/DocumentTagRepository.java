package com.flevin.knowgraph.server.repository.tag;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.flevin.knowgraph.server.model.tag.DocumentTag;
import com.flevin.knowgraph.server.model.tag.DocumentTagEvidence;
import com.flevin.knowgraph.server.model.tag.KnowledgeTag;
import com.flevin.knowgraph.server.repository.entity.DocumentTagEntity;
import com.flevin.knowgraph.server.repository.entity.DocumentTagEvidenceEntity;
import com.flevin.knowgraph.server.repository.entity.KnowledgeTagEntity;
import com.flevin.knowgraph.server.repository.mapper.DocumentTagEvidenceMapper;
import com.flevin.knowgraph.server.repository.mapper.DocumentTagMapper;
import com.flevin.knowgraph.server.repository.mapper.KnowledgeTagMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
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
            String spaceId,
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
            String spaceId,
            String tagId
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
            String spaceId,
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
            String spaceId,
            String documentTagId
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
            String spaceId,
            String sourceDocumentId
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
     * 查询一次标签运行实际新保存的文档标签关系。
     *
     * @param spaceId 知识空间标识
     * @param runId 标签运行标识
     * @return 按创建时间和标识排序的文档标签关系
     */
    public List<DocumentTag> findAllByExtractionRun(
            String spaceId,
            String runId
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
     * 批量查询当前空间内的标签定义。
     *
     * @param spaceId 知识空间标识
     * @param tagIds 标签标识列表
     * @return 标签定义列表
     */
    public List<KnowledgeTag> findTagsByIds(
            String spaceId,
            List<String> tagIds
    ) {
        if (tagIds.isEmpty()) {
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
     * 保存文档标签关系。
     *
     * @param documentTag 已通过领域校验的文档标签关系
     */
    public void saveDocumentTag(DocumentTag documentTag) {
        // 将领域关系转换为 MyBatis-Plus 实体并保存
        documentTagMapper.insert(toEntity(documentTag));
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
            String spaceId,
            String documentTagId
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
            String spaceId,
            List<String> documentTagIds
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

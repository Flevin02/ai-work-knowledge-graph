package com.flevin.knowgraph.server.repository.association;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.flevin.knowgraph.server.model.association.DocumentAssociationRun;
import com.flevin.knowgraph.server.repository.entity.DocumentAssociationRunEntity;
import com.flevin.knowgraph.server.repository.mapper.DocumentAssociationRunMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/**
 * 文档关联运行数据访问对象，负责运行记录的保存和空间隔离查询。
 */
@Repository
@RequiredArgsConstructor
public class DocumentAssociationRunRepository {

    private final DocumentAssociationRunMapper mapper;

    /**
     * 保存一条文档关联运行记录。
     *
     * @param run 文档关联运行领域模型
     */
    public void save(DocumentAssociationRun run) {
        // 将领域运行模型转换为 MyBatis-Plus 实体并插入历史记录
        mapper.insert(toEntity(run));
    }

    /**
     * 按空间、主体文档和运行标识查询文档关联运行。
     *
     * @param spaceId 知识空间标识
     * @param sourceDocumentId 主体文档标识
     * @param runId 运行标识
     * @return 匹配的运行记录；不存在时返回空
     */
    public Optional<DocumentAssociationRun> findById(
            String spaceId,
            String sourceDocumentId,
            String runId
    ) {
        // 使用三重边界查询，防止通过运行标识跨空间或跨文档读取
        DocumentAssociationRunEntity entity = mapper.selectOne(
                Wrappers.<DocumentAssociationRunEntity>lambdaQuery()
                        .eq(DocumentAssociationRunEntity::getSpaceId, spaceId)
                        .eq(DocumentAssociationRunEntity::getSourceDocumentId, sourceDocumentId)
                        .eq(DocumentAssociationRunEntity::getId, runId)
        );
        return Optional.ofNullable(entity).map(this::toDomain);
    }

    /**
     * 按知识空间和运行标识查询文档关联运行，供关系归属校验使用。
     *
     * @param spaceId 知识空间标识
     * @param runId 运行标识
     * @return 匹配的运行记录；不存在时返回空
     */
    public Optional<DocumentAssociationRun> findById(
            String spaceId,
            String runId
    ) {
        // 使用知识空间和运行标识读取运行快照，后续由 Service 校验主体文档关系
        DocumentAssociationRunEntity entity = mapper.selectOne(
                Wrappers.<DocumentAssociationRunEntity>lambdaQuery()
                        .eq(DocumentAssociationRunEntity::getSpaceId, spaceId)
                        .eq(DocumentAssociationRunEntity::getId, runId)
        );
        return Optional.ofNullable(entity).map(this::toDomain);
    }

    /**
     * 将持久化实体转换为文档关联运行领域模型。
     *
     * @param entity MyBatis-Plus 持久化实体
     * @return 文档关联运行领域模型
     */
    private DocumentAssociationRun toDomain(DocumentAssociationRunEntity entity) {
        return new DocumentAssociationRun(
                entity.getId(),
                entity.getSpaceId(),
                entity.getSourceDocumentId(),
                entity.getSourceContentHash(),
                entity.getStatus(),
                entity.getFailureStage(),
                entity.getErrorMessage(),
                valueOrZero(entity.getCandidateCount()),
                valueOrZero(entity.getComparedCount()),
                valueOrZero(entity.getSuggestionCount()),
                valueOrZero(entity.getTagCandidateCount()),
                valueOrZero(entity.getKeywordCandidateCount()),
                valueOrZero(entity.getSemanticCandidateCount()),
                entity.getPromptVersion(),
                entity.getSchemaVersion(),
                entity.getCandidateRecallPolicyVersion(),
                entity.getAssociationPolicyVersion(),
                entity.getEmbeddingProvider(),
                entity.getEmbeddingModel(),
                entity.getEmbeddingVersion(),
                entity.getTopK(),
                entity.getSimilarityThreshold(),
                valueOrZero(entity.getModelRequestCount()),
                valueOrZero(entity.getRetryCount()),
                entity.getDurationMs(),
                Instant.parse(entity.getCreatedAt()),
                entity.getCompletedAt() == null ? null : Instant.parse(entity.getCompletedAt())
        );
    }

    /**
     * 将文档关联运行领域模型转换为 MyBatis-Plus 实体。
     *
     * @param run 文档关联运行领域模型
     * @return MyBatis-Plus 持久化实体
     */
    private DocumentAssociationRunEntity toEntity(DocumentAssociationRun run) {
        DocumentAssociationRunEntity entity = new DocumentAssociationRunEntity();
        entity.setId(run.id());
        entity.setSpaceId(run.spaceId());
        entity.setSourceDocumentId(run.sourceDocumentId());
        entity.setSourceContentHash(run.sourceContentHash());
        entity.setStatus(run.status());
        entity.setFailureStage(run.failureStage());
        entity.setErrorMessage(run.errorMessage());
        entity.setCandidateCount(run.candidateCount());
        entity.setComparedCount(run.comparedCount());
        entity.setSuggestionCount(run.suggestionCount());
        entity.setTagCandidateCount(run.tagCandidateCount());
        entity.setKeywordCandidateCount(run.keywordCandidateCount());
        entity.setSemanticCandidateCount(run.semanticCandidateCount());
        entity.setPromptVersion(run.promptVersion());
        entity.setSchemaVersion(run.schemaVersion());
        entity.setCandidateRecallPolicyVersion(run.candidateRecallPolicyVersion());
        entity.setAssociationPolicyVersion(run.associationPolicyVersion());
        entity.setEmbeddingProvider(run.embeddingProvider());
        entity.setEmbeddingModel(run.embeddingModel());
        entity.setEmbeddingVersion(run.embeddingVersion());
        entity.setTopK(run.topK());
        entity.setSimilarityThreshold(run.similarityThreshold());
        entity.setModelRequestCount(run.modelRequestCount());
        entity.setRetryCount(run.retryCount());
        entity.setDurationMs(run.durationMs());
        entity.setCreatedAt(run.createdAt().toString());
        entity.setCompletedAt(run.completedAt() == null ? null : run.completedAt().toString());
        return entity;
    }

    private int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }
}

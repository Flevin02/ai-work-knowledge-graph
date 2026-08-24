package com.flevin.knowgraph.server.repository.association;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.flevin.knowgraph.server.model.association.DocumentAssociationRun;
import com.flevin.knowgraph.server.repository.entity.DocumentAssociationRunEntity;
import com.flevin.knowgraph.server.repository.mapper.DocumentAssociationRunMapper;
import com.flevin.knowgraph.server.repository.mapping.DocumentAssociationRunEntityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 文档关联运行数据访问对象，负责运行记录的保存和空间隔离查询。
 */
@Repository
@RequiredArgsConstructor
public class DocumentAssociationRunRepository {

    private final DocumentAssociationRunMapper mapper;
    private final DocumentAssociationRunEntityMapper entityMapper;

    /**
     * 保存一条文档关联运行记录。
     *
     * @param run 文档关联运行领域模型
     */
    public void save(DocumentAssociationRun run) {
        // 将领域运行模型转换为 MyBatis-Plus 实体并插入历史记录
        mapper.insert(entityMapper.toEntity(run));
    }

    /**
     * 将处理中的文档关联运行更新为完成或失败状态。
     *
     * @param run 带最终统计和完成时间的运行快照
     * @return 实际更新记录数
     */
    public int update(DocumentAssociationRun run) {
        DocumentAssociationRunEntity entity = new DocumentAssociationRunEntity();
        entity.setStatus(run.status());
        entity.setFailureStage(run.failureStage());
        entity.setErrorMessage(run.errorMessage());
        entity.setCandidateCount(run.candidateCount());
        entity.setComparedCount(run.comparedCount());
        entity.setSuggestionCount(run.suggestionCount());
        entity.setTagCandidateCount(run.tagCandidateCount());
        entity.setKeywordCandidateCount(run.keywordCandidateCount());
        entity.setSemanticCandidateCount(run.semanticCandidateCount());
        entity.setModelRequestCount(run.modelRequestCount());
        entity.setRetryCount(run.retryCount());
        entity.setDurationMs(run.durationMs());
        entity.setCompletedAt(run.completedAt() == null ? null : run.completedAt().toString());

        // 只允许按空间、主体文档和运行标识结束 processing 运行
        return mapper.update(
                entity,
                Wrappers.<DocumentAssociationRunEntity>lambdaUpdate()
                        .eq(DocumentAssociationRunEntity::getSpaceId, run.spaceId())
                        .eq(DocumentAssociationRunEntity::getSourceDocumentId, run.sourceDocumentId())
                        .eq(DocumentAssociationRunEntity::getId, run.id())
                        .eq(DocumentAssociationRunEntity::getStatus, "processing")
        );
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
        return Optional.ofNullable(entity).map(entityMapper::toDomain);
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
        return Optional.ofNullable(entity).map(entityMapper::toDomain);
    }

}

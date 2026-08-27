package com.flevin.knowgraph.server.repository.tag;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.flevin.knowgraph.server.model.tag.DocumentTaggingRun;
import com.flevin.knowgraph.server.repository.entity.DocumentTaggingRunEntity;
import com.flevin.knowgraph.server.repository.mapper.DocumentTaggingRunMapper;
import com.flevin.knowgraph.server.repository.mapping.DocumentTaggingRunEntityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 文档标签运行数据访问对象，负责运行快照保存和隔离查询。
 */
@Repository
@RequiredArgsConstructor
public class DocumentTaggingRunRepository {

    private final DocumentTaggingRunMapper mapper;
    private final DocumentTaggingRunEntityMapper entityMapper;

    /**
     * 保存一条 processing 标签运行。
     *
     * @param run 标签运行领域模型
     */
    public void save(DocumentTaggingRun run) {
        // 将标签运行转换为 MyBatis-Plus 实体并插入历史记录
        mapper.insert(entityMapper.toEntity(run));
    }

    /**
     * 将 processing 标签运行更新为完成或失败状态。
     *
     * @param run 带最终统计和完成时间的运行快照
     * @return 实际更新记录数
     */
    public int update(DocumentTaggingRun run) {
        DocumentTaggingRunEntity entity = new DocumentTaggingRunEntity();
        entity.setStatus(run.status());
        entity.setFailureStage(run.failureStage());
        entity.setErrorMessage(run.errorMessage());
        entity.setSummary(run.summary());
        entity.setChunkCount(run.chunkCount());
        entity.setContextCharacterCount(run.contextCharacterCount());
        entity.setSuggestionCount(run.suggestionCount());
        entity.setEvidenceFailureCount(run.evidenceFailureCount());
        entity.setModelRequestCount(run.modelRequestCount());
        entity.setRetryCount(run.retryCount());
        entity.setDurationMs(run.durationMs());
        entity.setCompletedAt(run.completedAt() == null ? null : run.completedAt().toString());

        // 只允许按空间、来源资料和运行标识结束 processing 运行
        return mapper.update(
                entity,
                Wrappers.<DocumentTaggingRunEntity>lambdaUpdate()
                        .eq(DocumentTaggingRunEntity::getSpaceId, run.spaceId())
                        .eq(DocumentTaggingRunEntity::getSourceDocumentId, run.sourceDocumentId())
                        .eq(DocumentTaggingRunEntity::getId, run.id())
                        .eq(DocumentTaggingRunEntity::getStatus, "processing")
        );
    }

    /**
     * 按空间、来源资料和运行标识查询标签运行。
     *
     * @param spaceId 知识空间标识
     * @param sourceDocumentId 来源资料标识
     * @param runId 标签运行标识
     * @return 匹配运行；不存在时返回空
     */
    public Optional<DocumentTaggingRun> findById(
            Long spaceId,
            Long sourceDocumentId,
            Long runId
    ) {
        // 使用三重隔离条件防止跨空间或跨文档恢复运行
        DocumentTaggingRunEntity entity = mapper.selectOne(
                Wrappers.<DocumentTaggingRunEntity>lambdaQuery()
                        .eq(DocumentTaggingRunEntity::getSpaceId, spaceId)
                        .eq(DocumentTaggingRunEntity::getSourceDocumentId, sourceDocumentId)
                        .eq(DocumentTaggingRunEntity::getId, runId)
        );

        // 将持久化实体转换为领域运行模型
        return Optional.ofNullable(entity).map(entityMapper::toDomain);
    }

    /**
     * 查询指定来源资料最近创建的一次标签运行。
     *
     * @param spaceId 知识空间标识
     * @param sourceDocumentId 来源资料标识
     * @return 最近一次运行；从未运行时返回空
     */
    public Optional<DocumentTaggingRun> findLatest(
            Long spaceId,
            Long sourceDocumentId
    ) {
        // 按创建时间倒序读取一条运行，供桌面 Web 刷新后恢复处理结果
        DocumentTaggingRunEntity entity = mapper.selectOne(
                Wrappers.<DocumentTaggingRunEntity>lambdaQuery()
                        .eq(DocumentTaggingRunEntity::getSpaceId, spaceId)
                        .eq(DocumentTaggingRunEntity::getSourceDocumentId, sourceDocumentId)
                        .orderByDesc(DocumentTaggingRunEntity::getCreatedAt)
                        .last("LIMIT 1")
        );

        // 将最近运行实体转换为领域模型
        return Optional.ofNullable(entity).map(entityMapper::toDomain);
    }
}

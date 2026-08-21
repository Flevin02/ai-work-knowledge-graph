package com.flevin.knowgraph.server.repository.ai;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.flevin.knowgraph.server.model.ai.DocumentExtractionOverview;
import com.flevin.knowgraph.server.repository.entity.AiExtractionRunEntity;
import com.flevin.knowgraph.server.repository.mapper.AiExtractionRunMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * AI 抽取运行数据访问对象，负责运行记录创建、完成、失败和历史查询。
 */
@Repository
@RequiredArgsConstructor
public class AiExtractionRunRepository {

    private final AiExtractionRunMapper aiExtractionRunMapper;

    /**
     * 保存刚创建的处理中抽取记录。
     *
     * @param entity 抽取运行实体
     */
    public void save(AiExtractionRunEntity entity) {
        // 使用 BaseMapper 保存 processing 状态的抽取记录
        aiExtractionRunMapper.insert(entity);
    }

    /**
     * 保存确定性解析得到的章节和分片总数，便于处理中或失败运行恢复进度边界。
     *
     * @param extractionId 抽取记录标识
     * @param sectionCount 章节数量
     * @param chunkCount 分片数量
     */
    public void plan(
            String extractionId,
            int sectionCount,
            int chunkCount
    ) {
        AiExtractionRunEntity updateEntity = new AiExtractionRunEntity();
        updateEntity.setSectionCount(sectionCount);
        updateEntity.setChunkCount(chunkCount);

        // 只按当前运行标识补充确定性解析统计，不改变 processing 状态
        LambdaUpdateWrapper<AiExtractionRunEntity> updateWrapper = Wrappers.lambdaUpdate();
        updateWrapper.eq(AiExtractionRunEntity::getId, extractionId);

        // 使用 BaseMapper 保存可恢复的章节和分片总数
        aiExtractionRunMapper.update(updateEntity, updateWrapper);
    }

    /**
     * 标记抽取运行完成并保存完整结果 JSON。
     *
     * @param extractionId 抽取记录标识
     * @param sectionCount 章节数量
     * @param chunkCount 分片数量
     * @param documentSummary 文档级全文摘要；生成失败时为空
     * @param documentSummaryPromptVersion 文档级全文摘要 Prompt 版本
     * @param documentSummaryStatus 文档级全文摘要状态
     * @param documentSummaryError 文档级全文摘要失败原因
     * @param resultJson 完整结构化结果 JSON
     * @param completedAt 完成时间
     */
    public void complete(
            String extractionId,
            int sectionCount,
            int chunkCount,
            String documentSummary,
            String documentSummaryPromptVersion,
            String documentSummaryStatus,
            String documentSummaryError,
            String resultJson,
            String completedAt
    ) {
        AiExtractionRunEntity updateEntity = new AiExtractionRunEntity();
        updateEntity.setStatus("completed");
        updateEntity.setSectionCount(sectionCount);
        updateEntity.setChunkCount(chunkCount);
        updateEntity.setDocumentSummary(documentSummary);
        updateEntity.setDocumentSummaryPromptVersion(documentSummaryPromptVersion);
        updateEntity.setDocumentSummaryStatus(documentSummaryStatus);
        updateEntity.setDocumentSummaryError(documentSummaryError);
        updateEntity.setResultJson(resultJson);
        updateEntity.setErrorMessage(null);
        updateEntity.setCompletedAt(completedAt);

        // 只更新当前抽取记录，保留创建时的模型和版本快照
        LambdaUpdateWrapper<AiExtractionRunEntity> updateWrapper = Wrappers.lambdaUpdate();
        updateWrapper.eq(AiExtractionRunEntity::getId, extractionId);

        // 使用 BaseMapper 写入完成状态和结构化结果
        aiExtractionRunMapper.update(updateEntity, updateWrapper);
    }

    /**
     * 标记抽取运行失败并保存错误摘要。
     *
     * @param extractionId 抽取记录标识
     * @param errorMessage 面向用户的失败摘要
     * @param completedAt 失败时间
     */
    public void fail(
            String extractionId,
            String errorMessage,
            String completedAt
    ) {
        AiExtractionRunEntity updateEntity = new AiExtractionRunEntity();
        updateEntity.setStatus("failed");
        updateEntity.setErrorMessage(errorMessage);
        updateEntity.setCompletedAt(completedAt);

        // 只更新当前抽取记录，避免失败影响其他文档或历史运行
        LambdaUpdateWrapper<AiExtractionRunEntity> updateWrapper = Wrappers.lambdaUpdate();
        updateWrapper.eq(AiExtractionRunEntity::getId, extractionId);

        // 使用 BaseMapper 保存失败状态和错误摘要
        aiExtractionRunMapper.update(updateEntity, updateWrapper);
    }

    /**
     * 查询一份来源资料的全部抽取运行，最新记录排在前面。
     *
     * @param spaceId 知识空间标识
     * @param documentId 来源资料标识
     * @return 抽取运行实体列表
     */
    public List<AiExtractionRunEntity> findAllByDocument(
            String spaceId,
            String documentId
    ) {
        // 按空间、文档和创建时间查询抽取记录，保持历史可追溯
        return aiExtractionRunMapper.selectList(
                Wrappers.<AiExtractionRunEntity>lambdaQuery()
                        .eq(AiExtractionRunEntity::getSpaceId, spaceId)
                        .eq(AiExtractionRunEntity::getSourceDocumentId, documentId)
                        .orderByDesc(AiExtractionRunEntity::getCreatedAt)
                        .orderByDesc(AiExtractionRunEntity::getId)
        );
    }

    /**
     * 查询指定来源资料的一条抽取运行。
     *
     * @param spaceId 知识空间标识
     * @param documentId 来源资料标识
     * @param extractionId 抽取记录标识
     * @return 抽取运行实体；不存在时返回空
     */
    public Optional<AiExtractionRunEntity> findById(
            String spaceId,
            String documentId,
            String extractionId
    ) {
        // 使用空间、文档和运行标识三重边界查询单条抽取记录
        AiExtractionRunEntity entity = aiExtractionRunMapper.selectOne(
                Wrappers.<AiExtractionRunEntity>lambdaQuery()
                        .eq(AiExtractionRunEntity::getSpaceId, spaceId)
                        .eq(AiExtractionRunEntity::getSourceDocumentId, documentId)
                        .eq(AiExtractionRunEntity::getId, extractionId)
        );
        return Optional.ofNullable(entity);
    }

    /**
     * 批量查询当前页来源资料的最近抽取运行、最近成功结果标识和摘要。
     *
     * @param spaceId 知识空间标识
     * @param documentIds 当前页来源资料标识
     * @return 存在抽取记录的资料概览；未开始资料不返回记录
     */
    public List<DocumentExtractionOverview> findLatestByDocuments(
            String spaceId,
            List<String> documentIds
    ) {
        if (documentIds.isEmpty()) {
            return List.of();
        }

        // 使用窗口查询一次读取当前页全部文档的最近运行和最近成功记录
        List<AiExtractionRunEntity> entities = aiExtractionRunMapper.findLatestByDocumentIds(
                spaceId,
                documentIds
        );

        // 将持久化查询结果转换为不携带完整模型输出的领域概览
        return entities.stream()
                .map(entity -> new DocumentExtractionOverview(
                        entity.getSourceDocumentId(),
                        entity.getId(),
                        entity.getStatus(),
                        Instant.parse(entity.getCreatedAt()),
                        entity.getCompletedAt() == null ? null : Instant.parse(entity.getCompletedAt()),
                        entity.getErrorMessage(),
                        entity.getLatestCompletedExtractionId(),
                        entity.getLatestCompletedSummary()
                ))
                .toList();
    }
}

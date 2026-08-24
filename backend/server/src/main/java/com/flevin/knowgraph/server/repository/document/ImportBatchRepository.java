package com.flevin.knowgraph.server.repository.document;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.flevin.knowgraph.server.model.document.ImportBatch;
import com.flevin.knowgraph.server.repository.entity.ImportBatchEntity;
import com.flevin.knowgraph.server.repository.mapper.ImportBatchMapper;
import com.flevin.knowgraph.server.repository.mapping.ImportBatchEntityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;

/**
 * 来源资料导入批次数据访问对象，使用 MyBatis-Plus 简化插入和状态更新。
 */
@Repository
@RequiredArgsConstructor
public class ImportBatchRepository {

    private final ImportBatchMapper importBatchMapper;
    private final ImportBatchEntityMapper entityMapper;

    /**
     * 新增一条待处理的来源资料导入批次。
     *
     * @param batch 初始导入批次
     */
    public void save(ImportBatch batch) {
        // 将领域批次转换为 MyBatis-Plus 持久化实体
        ImportBatchEntity entity = entityMapper.toEntity(batch);

        // 使用 BaseMapper 插入批次初始状态
        importBatchMapper.insert(entity);
    }

    /**
     * 更新导入批次的最终状态和各类文件数量。
     *
     * @param batchId 导入批次标识
     * @param status 批次最终状态
     * @param importedCount 成功导入数
     * @param duplicateCount 重复内容数
     * @param failedCount 处理失败数
     * @param completedAt 批次完成时间
     */
    public void complete(
            String batchId,
            String status,
            int importedCount,
            int duplicateCount,
            int failedCount,
            Instant completedAt
    ) {
        ImportBatchEntity updateEntity = new ImportBatchEntity();
        updateEntity.setStatus(status);
        updateEntity.setImportedCount(importedCount);
        updateEntity.setDuplicateCount(duplicateCount);
        updateEntity.setFailedCount(failedCount);
        updateEntity.setCompletedAt(completedAt.toString());

        // 只按批次主键更新最终统计，避免重复查询批次对象
        LambdaUpdateWrapper<ImportBatchEntity> updateWrapper = Wrappers.lambdaUpdate();
        updateWrapper.eq(ImportBatchEntity::getId, batchId);

        // 使用 BaseMapper 写入批次最终状态和分类统计
        importBatchMapper.update(updateEntity, updateWrapper);
    }

}

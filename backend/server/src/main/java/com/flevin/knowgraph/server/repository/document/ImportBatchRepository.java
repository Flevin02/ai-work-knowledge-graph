package com.flevin.knowgraph.server.repository.document;

import com.flevin.knowgraph.server.model.document.ImportBatch;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;

/**
 * 来源资料导入批次数据访问对象。
 */
@Repository
public class ImportBatchRepository {

    private static final String INSERT_SQL = """
            INSERT INTO import_batches (
                id, status, total_count, imported_count, duplicate_count,
                failed_count, created_at, completed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String COMPLETE_SQL = """
            UPDATE import_batches
            SET status = ?, imported_count = ?, duplicate_count = ?, failed_count = ?, completed_at = ?
            WHERE id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public ImportBatchRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 新增一条待处理的来源资料导入批次。
     *
     * @param batch 初始导入批次
     */
    public void save(ImportBatch batch) {
        // 保存批次初始状态，为后续逐文件结果提供可追溯的批次标识
        jdbcTemplate.update(
                INSERT_SQL,
                batch.id(),
                batch.status(),
                batch.totalCount(),
                batch.importedCount(),
                batch.duplicateCount(),
                batch.failedCount(),
                batch.createdAt().toString(),
                batch.completedAt() == null ? null : batch.completedAt().toString()
        );
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
        // 写入批次最终统计，保留成功、重复和失败的明确边界
        jdbcTemplate.update(
                COMPLETE_SQL,
                status,
                importedCount,
                duplicateCount,
                failedCount,
                completedAt.toString(),
                batchId
        );
    }
}

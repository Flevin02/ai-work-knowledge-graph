package com.flevin.knowgraph.server.model.document;

import java.time.Instant;

/**
 * 来源资料导入批次模型，用于记录一次 multipart 请求的整体处理结果。
 *
 * @param id 导入批次标识
 * @param spaceId 所属知识空间标识
 * @param status 批次状态
 * @param totalCount 文件总数
 * @param importedCount 成功导入数
 * @param duplicateCount 重复内容数
 * @param failedCount 处理失败数
 * @param createdAt 批次创建时间
 * @param completedAt 批次完成时间
 */
public record ImportBatch(
        String id,
        String spaceId,
        String status,
        int totalCount,
        int importedCount,
        int duplicateCount,
        int failedCount,
        Instant createdAt,
        Instant completedAt
) {
}

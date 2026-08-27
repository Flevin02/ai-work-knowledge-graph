package com.flevin.knowgraph.server.model.document;

import java.time.Instant;

/**
 * 已持久化的来源资料模型，包含原始文件定位和解析后的文本内容。
 *
 * @param id 来源资料标识
 * @param spaceId 所属知识空间标识
 * @param batchId 首次导入批次标识
 * @param name 原始文件名
 * @param kind 文件类型
 * @param documentType 文档业务类型
 * @param contentHash SHA-256 内容指纹
 * @param storagePath 原始文件本地保存路径
 * @param contentText 解析后的完整文本
 * @param excerpt 文本摘要预览
 * @param status 来源资料状态
 * @param fileSize 文件字节数
 * @param importedAt 首次导入时间
 * @param updatedAt 最近更新时间
 */
public record SourceDocument(
        Long id,
        Long spaceId,
        Long batchId,
        String name,
        String kind,
        SourceDocumentType documentType,
        String contentHash,
        String storagePath,
        String contentText,
        String excerpt,
        String status,
        long fileSize,
        Instant importedAt,
        Instant updatedAt
) {
}

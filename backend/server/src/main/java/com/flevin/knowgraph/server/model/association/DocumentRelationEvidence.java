package com.flevin.knowgraph.server.model.association;

import java.time.Instant;

/**
 * 文档关系证据领域模型。
 *
 * @param id 证据标识
 * @param spaceId 所属知识空间标识
 * @param documentRelationId 被证据支撑的文档关系标识
 * @param sourceDocumentId 证据实际所在的来源资料标识
 * @param chunkId 当前文档内稳定的分片标识
 * @param sectionPath 分片所属章节路径
 * @param quote 可逐字反查的原文片段
 * @param startOffset 原文起始偏移
 * @param endOffset 原文结束偏移，不包含该位置字符
 * @param evidenceRole 证据角色：source、target 或 cross_reference
 * @param createdAt 创建时间
 */
public record DocumentRelationEvidence(
        Long id,
        Long spaceId,
        Long documentRelationId,
        Long sourceDocumentId,
        String chunkId,
        String sectionPath,
        String quote,
        Integer startOffset,
        Integer endOffset,
        String evidenceRole,
        Instant createdAt
) {
}

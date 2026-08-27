package com.flevin.knowgraph.server.model.association;

import java.time.Instant;

/**
 * 文档关系领域模型。
 *
 * @param id 文档关系标识
 * @param spaceId 所属知识空间标识
 * @param sourceDocumentId 关系主体文档标识；对称关系为规范化排序后的左侧文档
 * @param targetDocumentId 关系客体文档标识；对称关系为规范化排序后的右侧文档
 * @param relationType 五种白名单关系类型
 * @param direction 关系方向
 * @param status 关系状态
 * @param generationMode 关系生成方式
 * @param confidence 模型或规则置信度
 * @param reason 关系原因
 * @param associationRunId 产生关系的运行标识；手工关系为空
 * @param sourceContentHash 主体文档内容指纹快照
 * @param targetContentHash 客体文档内容指纹快照
 * @param associationPolicyVersion 关联策略版本
 * @param relationKey 稳定幂等键
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间
 */
public record DocumentRelation(
        Long id,
        Long spaceId,
        Long sourceDocumentId,
        Long targetDocumentId,
        String relationType,
        String direction,
        String status,
        String generationMode,
        double confidence,
        String reason,
        Long associationRunId,
        String sourceContentHash,
        String targetContentHash,
        String associationPolicyVersion,
        String relationKey,
        Instant createdAt,
        Instant updatedAt
) {
}

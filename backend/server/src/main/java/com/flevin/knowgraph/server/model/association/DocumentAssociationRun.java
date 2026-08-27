package com.flevin.knowgraph.server.model.association;

import java.time.Instant;

/**
 * 文档关联运行领域模型。
 *
 * @param id 运行标识
 * @param spaceId 所属知识空间标识
 * @param sourceDocumentId 本次作为关联主体的来源资料标识
 * @param sourceContentHash 运行开始时主体文档内容指纹
 * @param status 运行状态
 * @param failureStage 失败阶段；成功或未失败时为空
 * @param errorMessage 稳定错误摘要
 * @param candidateCount 召回候选数量
 * @param comparedCount 实际比较候选数量
 * @param suggestionCount 保存的关系建议数量
 * @param tagCandidateCount 标签通道候选数量
 * @param keywordCandidateCount 关键词、标题和显式引用通道候选数量
 * @param semanticCandidateCount 语义通道候选数量
 * @param promptVersion 关联判断 Prompt 版本
 * @param schemaVersion 关联判断 Schema 版本
 * @param candidateRecallPolicyVersion 候选召回策略版本
 * @param associationPolicyVersion 文档关联策略版本
 * @param embeddingProvider Embedding 供应商快照
 * @param embeddingModel Embedding 模型快照
 * @param embeddingVersion Embedding 版本快照
 * @param topK 召回 TopK
 * @param similarityThreshold 相似度阈值
 * @param modelRequestCount 模型请求次数
 * @param retryCount 重试次数
 * @param durationMs 运行耗时毫秒
 * @param createdAt 创建时间
 * @param completedAt 完成或失败时间
 */
public record DocumentAssociationRun(
        Long id,
        Long spaceId,
        Long sourceDocumentId,
        String sourceContentHash,
        String status,
        String failureStage,
        String errorMessage,
        int candidateCount,
        int comparedCount,
        int suggestionCount,
        int tagCandidateCount,
        int keywordCandidateCount,
        int semanticCandidateCount,
        String promptVersion,
        String schemaVersion,
        String candidateRecallPolicyVersion,
        String associationPolicyVersion,
        String embeddingProvider,
        String embeddingModel,
        String embeddingVersion,
        Integer topK,
        Double similarityThreshold,
        int modelRequestCount,
        int retryCount,
        Long durationMs,
        Instant createdAt,
        Instant completedAt
) {
}

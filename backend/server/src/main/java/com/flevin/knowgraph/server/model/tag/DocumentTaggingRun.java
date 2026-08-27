package com.flevin.knowgraph.server.model.tag;

import java.time.Instant;

/**
 * 一次独立文档标签抽取运行。
 *
 * @param id 运行标识
 * @param spaceId 所属知识空间标识
 * @param sourceDocumentId 当前来源资料标识
 * @param sourceContentHash 运行开始时的内容指纹
 * @param status processing、completed 或 failed
 * @param failureStage 失败阶段；成功或处理中为空
 * @param errorMessage 脱敏后的稳定错误摘要
 * @param summary 通过结构校验的模型摘要
 * @param chunkCount 提供给标签客户端的分片数量
 * @param contextCharacterCount 提供给标签客户端的分片字符总数
 * @param suggestionCount 本次新保存的建议数量
 * @param evidenceFailureCount 因逐字证据失败未物化的候选数量
 * @param promptVersion 标签 Prompt 版本
 * @param schemaVersion 标签 Schema 版本
 * @param modelRequestCount 模型请求次数
 * @param retryCount 重试次数
 * @param durationMs 运行耗时毫秒
 * @param createdAt 创建时间
 * @param completedAt 完成或失败时间
 */
public record DocumentTaggingRun(
        Long id,
        Long spaceId,
        Long sourceDocumentId,
        String sourceContentHash,
        String status,
        String failureStage,
        String errorMessage,
        String summary,
        int chunkCount,
        int contextCharacterCount,
        int suggestionCount,
        int evidenceFailureCount,
        String promptVersion,
        String schemaVersion,
        int modelRequestCount,
        int retryCount,
        Long durationMs,
        Instant createdAt,
        Instant completedAt
) {
}

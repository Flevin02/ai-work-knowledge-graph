package com.flevin.knowgraph.server.model.association;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * 文档关联运行状态和已通过校验的建议结果。
 *
 * @param runId 关联运行标识
 * @param sourceDocumentId 当前分析文档标识
 * @param status 运行状态
 * @param failureStage 失败阶段；成功时为空
 * @param errorMessage 脱敏后的失败或部分过滤说明
 * @param candidateCount 召回候选数量
 * @param comparedCount 实际判断候选数量
 * @param suggestionCount 本次新保存的建议数量
 * @param tagCandidateCount 已确认标签通道候选数量
 * @param keywordCandidateCount 默认内容通道候选数量
 * @param semanticCandidateCount 语义融合通道补充候选数量
 * @param promptVersion Prompt 版本
 * @param schemaVersion Schema 版本
 * @param candidateRecallPolicyVersion 候选召回策略版本
 * @param associationPolicyVersion 文档关联策略版本
 * @param relations 本次运行新保存的关系建议
 * @param createdAt 创建时间
 * @param completedAt 完成或失败时间
 */
@Schema(description = "文档关联运行状态和建议结果")
public record DocumentAssociationRunResponse(
        @Schema(description = "关联运行标识") Long runId,
        @Schema(description = "当前分析文档标识") Long sourceDocumentId,
        @Schema(description = "运行状态", example = "completed") String status,
        @Schema(description = "失败阶段", example = "evidence_invalid") String failureStage,
        @Schema(description = "脱敏后的错误或过滤说明") String errorMessage,
        @Schema(description = "召回候选数量", example = "2") int candidateCount,
        @Schema(description = "实际判断候选数量", example = "2") int comparedCount,
        @Schema(description = "本次新保存的建议数量", example = "1") int suggestionCount,
        @Schema(description = "已确认标签通道候选数量", example = "1") int tagCandidateCount,
        @Schema(description = "默认内容通道候选数量", example = "1") int keywordCandidateCount,
        @Schema(description = "语义融合通道补充候选数量", example = "1") int semanticCandidateCount,
        @Schema(description = "Prompt 版本", example = "document-association-v1") String promptVersion,
        @Schema(description = "Schema 版本", example = "document-association-v1") String schemaVersion,
        @Schema(description = "候选召回策略版本", example = "document-candidate-recall-v1") String candidateRecallPolicyVersion,
        @Schema(description = "关联策略版本", example = "document-association-policy-v1") String associationPolicyVersion,
        @Schema(description = "本次运行新保存的关系建议") List<DocumentRelationResponse> relations,
        @Schema(description = "创建时间") Instant createdAt,
        @Schema(description = "完成或失败时间") Instant completedAt
) {

    public DocumentAssociationRunResponse {
        relations = List.copyOf(relations);
    }
}

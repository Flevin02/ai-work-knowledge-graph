package com.flevin.knowgraph.server.model.tag;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * 标签运行状态和本次新保存的建议。
 *
 * @param runId 标签运行标识
 * @param sourceDocumentId 当前来源资料标识
 * @param status 运行状态
 * @param failureStage 失败阶段
 * @param errorMessage 脱敏后的错误摘要
 * @param summary 通过结构校验的文档摘要
 * @param chunkCount 本次输入分片数量
 * @param contextCharacterCount 本次输入分片字符总数
 * @param suggestionCount 本次新保存建议数量
 * @param evidenceFailureCount 证据失败候选数量
 * @param promptVersion Prompt 版本
 * @param schemaVersion Schema 版本
 * @param suggestions 本次新保存的标签建议
 * @param createdAt 创建时间
 * @param completedAt 完成或失败时间
 */
@Schema(description = "文档标签运行状态和建议结果")
public record DocumentTaggingRunResponse(
        @Schema(description = "标签运行标识") String runId,
        @Schema(description = "当前来源资料标识") String sourceDocumentId,
        @Schema(description = "运行状态", example = "completed") String status,
        @Schema(description = "失败阶段", example = "evidence_invalid") String failureStage,
        @Schema(description = "脱敏后的错误摘要") String errorMessage,
        @Schema(description = "通过结构校验的文档摘要") String summary,
        @Schema(description = "输入分片数量", example = "2") int chunkCount,
        @Schema(description = "输入分片字符总数", example = "1200") int contextCharacterCount,
        @Schema(description = "本次新保存建议数量", example = "1") int suggestionCount,
        @Schema(description = "证据失败候选数量", example = "0") int evidenceFailureCount,
        @Schema(description = "Prompt 版本", example = "document-tag-v1") String promptVersion,
        @Schema(description = "Schema 版本", example = "document-tag-v1") String schemaVersion,
        @Schema(description = "本次新保存的标签建议") List<DocumentTagSuggestionResponse> suggestions,
        @Schema(description = "创建时间") Instant createdAt,
        @Schema(description = "完成或失败时间") Instant completedAt
) {

    public DocumentTaggingRunResponse {
        suggestions = List.copyOf(suggestions);
    }
}

package com.flevin.knowgraph.server.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档关联运行 MyBatis-Plus 持久化实体。
 */
@Data
@NoArgsConstructor
@TableName("document_association_runs")
public class DocumentAssociationRunEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField("space_id")
    private String spaceId;

    @TableField("source_document_id")
    private String sourceDocumentId;

    @TableField("source_content_hash")
    private String sourceContentHash;

    private String status;

    @TableField("failure_stage")
    private String failureStage;

    @TableField("error_message")
    private String errorMessage;

    @TableField("candidate_count")
    private Integer candidateCount;

    @TableField("compared_count")
    private Integer comparedCount;

    @TableField("suggestion_count")
    private Integer suggestionCount;

    @TableField("tag_candidate_count")
    private Integer tagCandidateCount;

    @TableField("keyword_candidate_count")
    private Integer keywordCandidateCount;

    @TableField("semantic_candidate_count")
    private Integer semanticCandidateCount;

    @TableField("prompt_version")
    private String promptVersion;

    @TableField("schema_version")
    private String schemaVersion;

    @TableField("candidate_recall_policy_version")
    private String candidateRecallPolicyVersion;

    @TableField("association_policy_version")
    private String associationPolicyVersion;

    @TableField("embedding_provider")
    private String embeddingProvider;

    @TableField("embedding_model")
    private String embeddingModel;

    @TableField("embedding_version")
    private String embeddingVersion;

    @TableField("top_k")
    private Integer topK;

    @TableField("similarity_threshold")
    private Double similarityThreshold;

    @TableField("model_request_count")
    private Integer modelRequestCount;

    @TableField("retry_count")
    private Integer retryCount;

    @TableField("duration_ms")
    private Long durationMs;

    @TableField("created_at")
    private String createdAt;

    @TableField("completed_at")
    private String completedAt;
}

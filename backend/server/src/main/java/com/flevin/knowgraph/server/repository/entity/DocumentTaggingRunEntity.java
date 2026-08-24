package com.flevin.knowgraph.server.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档标签运行 MyBatis-Plus 持久化实体。
 */
@Data
@NoArgsConstructor
@TableName("document_tagging_runs")
public class DocumentTaggingRunEntity {

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

    private String summary;

    @TableField("chunk_count")
    private Integer chunkCount;

    @TableField("context_character_count")
    private Integer contextCharacterCount;

    @TableField("suggestion_count")
    private Integer suggestionCount;

    @TableField("evidence_failure_count")
    private Integer evidenceFailureCount;

    @TableField("prompt_version")
    private String promptVersion;

    @TableField("schema_version")
    private String schemaVersion;

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

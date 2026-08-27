package com.flevin.knowgraph.server.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI 抽取运行 MyBatis-Plus 持久化实体。
 */
@Data
@NoArgsConstructor
@TableName("ai_extraction_runs")
public class AiExtractionRunEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    @TableField("space_id")
    private Long spaceId;

    @TableField("source_document_id")
    private Long sourceDocumentId;

    private String provider;

    private String model;

    @TableField("prompt_version")
    private String promptVersion;

    @TableField("schema_version")
    private String schemaVersion;

    private String status;

    @TableField("section_count")
    private Integer sectionCount;

    @TableField("chunk_count")
    private Integer chunkCount;

    @TableField("result_json")
    private String resultJson;

    @TableField("document_summary")
    private String documentSummary;

    @TableField("document_summary_prompt_version")
    private String documentSummaryPromptVersion;

    @TableField("document_summary_status")
    private String documentSummaryStatus;

    @TableField("document_summary_error")
    private String documentSummaryError;

    @TableField("error_message")
    private String errorMessage;

    @TableField("created_at")
    private String createdAt;

    @TableField("completed_at")
    private String completedAt;

    @TableField(exist = false)
    private String latestCompletedExtractionId;

    @TableField(exist = false)
    private String latestCompletedSummary;
}

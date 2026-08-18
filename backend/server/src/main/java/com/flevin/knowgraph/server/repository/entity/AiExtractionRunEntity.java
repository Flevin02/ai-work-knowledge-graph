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
    private String id;

    @TableField("space_id")
    private String spaceId;

    @TableField("source_document_id")
    private String sourceDocumentId;

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

    @TableField("error_message")
    private String errorMessage;

    @TableField("created_at")
    private String createdAt;

    @TableField("completed_at")
    private String completedAt;
}

package com.flevin.knowgraph.server.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档标签关系 MyBatis-Plus 持久化实体。
 */
@Data
@NoArgsConstructor
@TableName("document_tags")
public class DocumentTagEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    @TableField("space_id")
    private Long spaceId;

    @TableField("source_document_id")
    private Long sourceDocumentId;

    @TableField("tag_id")
    private Long tagId;

    @TableField("source_type")
    private String sourceType;

    private String status;

    private Double confidence;

    @TableField("extraction_run_id")
    private Long extractionRunId;

    @TableField("content_hash")
    private String contentHash;

    @TableField("prompt_version")
    private String promptVersion;

    @TableField("schema_version")
    private String schemaVersion;

    @TableField("document_tag_key")
    private String documentTagKey;

    @TableField("created_at")
    private String createdAt;

    @TableField("updated_at")
    private String updatedAt;
}

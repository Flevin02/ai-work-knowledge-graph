package com.flevin.knowgraph.server.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档关系 MyBatis-Plus 持久化实体。
 */
@Data
@NoArgsConstructor
@TableName("document_relations")
public class DocumentRelationEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    @TableField("space_id")
    private Long spaceId;

    @TableField("source_document_id")
    private Long sourceDocumentId;

    @TableField("target_document_id")
    private Long targetDocumentId;

    @TableField("relation_type")
    private String relationType;

    private String direction;

    private String status;

    @TableField("generation_mode")
    private String generationMode;

    private Double confidence;

    private String reason;

    @TableField("association_run_id")
    private Long associationRunId;

    @TableField("source_content_hash")
    private String sourceContentHash;

    @TableField("target_content_hash")
    private String targetContentHash;

    @TableField("association_policy_version")
    private String associationPolicyVersion;

    @TableField("relation_key")
    private String relationKey;

    @TableField("created_at")
    private String createdAt;

    @TableField("updated_at")
    private String updatedAt;
}

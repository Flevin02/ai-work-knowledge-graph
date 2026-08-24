package com.flevin.knowgraph.server.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档关系证据 MyBatis-Plus 持久化实体。
 */
@Data
@NoArgsConstructor
@TableName("document_relation_evidences")
public class DocumentRelationEvidenceEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField("space_id")
    private String spaceId;

    @TableField("document_relation_id")
    private String documentRelationId;

    @TableField("source_document_id")
    private String sourceDocumentId;

    @TableField("chunk_id")
    private String chunkId;

    @TableField("section_path")
    private String sectionPath;

    private String quote;

    @TableField("start_offset")
    private Integer startOffset;

    @TableField("end_offset")
    private Integer endOffset;

    @TableField("evidence_role")
    private String evidenceRole;

    @TableField("created_at")
    private String createdAt;
}

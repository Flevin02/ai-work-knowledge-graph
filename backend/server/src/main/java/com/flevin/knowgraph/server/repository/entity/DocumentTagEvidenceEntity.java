package com.flevin.knowgraph.server.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档标签证据 MyBatis-Plus 持久化实体。
 */
@Data
@NoArgsConstructor
@TableName("document_tag_evidences")
public class DocumentTagEvidenceEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField("space_id")
    private String spaceId;

    @TableField("document_tag_id")
    private String documentTagId;

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

    @TableField("created_at")
    private String createdAt;
}

package com.flevin.knowgraph.server.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 来源资料章节 MyBatis-Plus 持久化实体。
 */
@Data
@NoArgsConstructor
@TableName("document_sections")
public class DocumentSectionEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    @TableField("space_id")
    private Long spaceId;

    @TableField("source_document_id")
    private Long sourceDocumentId;

    @TableField("section_id")
    private String sectionId;

    @TableField("parser_version")
    private String parserVersion;

    private String title;

    private Integer level;

    @TableField("section_path")
    private String sectionPath;

    private Integer ordinal;

    @TableField("content_text")
    private String contentText;

    @TableField("start_offset")
    private Integer startOffset;

    @TableField("end_offset")
    private Integer endOffset;

    @TableField("content_hash")
    private String contentHash;

    @TableField("created_at")
    private String createdAt;

    @TableField("updated_at")
    private String updatedAt;
}

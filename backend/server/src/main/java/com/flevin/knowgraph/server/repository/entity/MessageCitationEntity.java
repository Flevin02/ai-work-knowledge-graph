package com.flevin.knowgraph.server.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 回答引用 MyBatis-Plus 持久化实体。
 */
@Data
@NoArgsConstructor
@TableName("message_citations")
public class MessageCitationEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    @TableField("space_id")
    private Long spaceId;

    @TableField("message_id")
    private Long messageId;

    @TableField("source_document_id")
    private Long sourceDocumentId;

    @TableField("document_content_hash")
    private String documentContentHash;

    @TableField("chunk_record_id")
    private Long chunkRecordId;

    @TableField("chunk_id")
    private String chunkId;

    @TableField("section_path")
    private String sectionPath;

    private String quote;

    @TableField("start_offset")
    private Integer startOffset;

    @TableField("end_offset")
    private Integer endOffset;

    @TableField("citation_order")
    private Integer citationOrder;

    @TableField("validation_status")
    private String validationStatus;

    @TableField("retrieval_channel")
    private String retrievalChannel;

    @TableField("created_at")
    private String createdAt;
}

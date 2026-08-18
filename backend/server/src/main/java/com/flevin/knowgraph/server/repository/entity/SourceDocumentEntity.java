package com.flevin.knowgraph.server.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 来源资料 MyBatis-Plus 持久化实体。
 */
@Data
@NoArgsConstructor
@TableName("source_documents")
public class SourceDocumentEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField("space_id")
    private String spaceId;

    @TableField("batch_id")
    private String batchId;

    private String name;

    private String kind;

    @TableField("document_type")
    private String documentType;

    @TableField("content_hash")
    private String contentHash;

    @TableField("storage_path")
    private String storagePath;

    @TableField("content_text")
    private String contentText;

    private String excerpt;

    private String status;

    @TableField("file_size")
    private Long fileSize;

    @TableField("imported_at")
    private String importedAt;

    @TableField("updated_at")
    private String updatedAt;
}

package com.flevin.knowgraph.server.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 分片向量索引状态 MyBatis-Plus 持久化实体。
 */
@Data
@NoArgsConstructor
@TableName("document_chunk_index_states")
public class DocumentChunkIndexStateEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    @TableField("space_id")
    private Long spaceId;

    @TableField("source_document_id")
    private Long sourceDocumentId;

    @TableField("chunk_record_id")
    private Long chunkRecordId;

    @TableField("chunk_id")
    private String chunkId;

    @TableField("content_hash")
    private String contentHash;

    @TableField("chunk_version")
    private String chunkVersion;

    @TableField("embedding_provider")
    private String embeddingProvider;

    @TableField("embedding_model")
    private String embeddingModel;

    @TableField("embedding_version")
    private String embeddingVersion;

    private Integer dimension;

    @TableField("vector_json")
    private String vectorJson;

    private String status;

    @TableField("error_message")
    private String errorMessage;

    @TableField("created_at")
    private String createdAt;

    @TableField("updated_at")
    private String updatedAt;
}

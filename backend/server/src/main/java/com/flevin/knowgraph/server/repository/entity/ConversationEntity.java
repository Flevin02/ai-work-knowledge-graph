package com.flevin.knowgraph.server.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 只读问答会话 MyBatis-Plus 持久化实体。
 */
@Data
@NoArgsConstructor
@TableName("conversations")
public class ConversationEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    @TableField("space_id")
    private Long spaceId;

    private String title;

    @TableField("scope_source_document_id")
    private Long scopeSourceDocumentId;

    private String status;

    @TableField("created_at")
    private String createdAt;

    @TableField("updated_at")
    private String updatedAt;
}

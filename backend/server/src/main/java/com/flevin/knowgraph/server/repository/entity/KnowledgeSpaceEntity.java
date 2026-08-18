package com.flevin.knowgraph.server.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 知识空间 MyBatis-Plus 持久化实体，与领域层 KnowledgeSpace record 隔离。
 */
@Data
@NoArgsConstructor
@TableName("knowledge_spaces")
public class KnowledgeSpaceEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    private String name;

    private String description;

    private String status;

    @TableField("created_at")
    private String createdAt;

    @TableField("updated_at")
    private String updatedAt;
}

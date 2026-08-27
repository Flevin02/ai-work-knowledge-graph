package com.flevin.knowgraph.server.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 标签字典 MyBatis-Plus 持久化实体。
 */
@Data
@NoArgsConstructor
@TableName("tags")
public class KnowledgeTagEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    @TableField("space_id")
    private Long spaceId;

    private String name;

    @TableField("normalized_key")
    private String normalizedKey;

    private String status;

    @TableField("created_at")
    private String createdAt;

    @TableField("updated_at")
    private String updatedAt;
}

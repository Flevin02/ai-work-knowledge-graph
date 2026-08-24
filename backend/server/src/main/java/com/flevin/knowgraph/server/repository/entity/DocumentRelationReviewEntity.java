package com.flevin.knowgraph.server.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档关系审核历史 MyBatis-Plus 持久化实体。
 */
@Data
@NoArgsConstructor
@TableName("document_relation_reviews")
public class DocumentRelationReviewEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField("space_id")
    private String spaceId;

    @TableField("document_relation_id")
    private String documentRelationId;

    private String action;

    private String reason;

    @TableField("operator_name")
    private String operatorName;

    @TableField("created_at")
    private String createdAt;
}

package com.flevin.knowgraph.server.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 图谱关系审核动作 MyBatis-Plus 持久化实体。
 */
@Data
@NoArgsConstructor
@TableName("review_actions")
public class ReviewActionEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField("space_id")
    private String spaceId;

    @TableField("edge_id")
    private String edgeId;

    private String action;

    private String reason;

    @TableField("operator_name")
    private String operatorName;

    @TableField("created_at")
    private String createdAt;
}

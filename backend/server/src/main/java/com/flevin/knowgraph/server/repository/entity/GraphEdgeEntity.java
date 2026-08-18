package com.flevin.knowgraph.server.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 图谱关系 MyBatis-Plus 持久化实体。
 */
@Data
@NoArgsConstructor
@TableName("graph_edges")
public class GraphEdgeEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField("space_id")
    private String spaceId;

    @TableField("source_node_id")
    private String sourceNodeId;

    @TableField("target_node_id")
    private String targetNodeId;

    @TableField("relation_type")
    private String relationType;

    private String status;

    private Double confidence;

    @TableField("created_at")
    private String createdAt;

    @TableField("updated_at")
    private String updatedAt;
}

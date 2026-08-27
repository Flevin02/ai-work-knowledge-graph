package com.flevin.knowgraph.server.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 图谱节点 MyBatis-Plus 持久化实体。
 */
@Data
@NoArgsConstructor
@TableName("graph_nodes")
public class GraphNodeEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    @TableField("space_id")
    private Long spaceId;

    @TableField("node_type")
    private String nodeType;

    private String label;

    private String summary;

    private String status;

    @TableField("normalized_key")
    private String normalizedKey;

    @TableField("source_ids_json")
    private String sourceIdsJson;

    @TableField("created_at")
    private String createdAt;

    @TableField("updated_at")
    private String updatedAt;
}

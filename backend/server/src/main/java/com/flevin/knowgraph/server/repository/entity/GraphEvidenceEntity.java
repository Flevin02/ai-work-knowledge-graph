package com.flevin.knowgraph.server.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 图谱证据 MyBatis-Plus 持久化实体。
 */
@Data
@NoArgsConstructor
@TableName("evidences")
public class GraphEvidenceEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    @TableField("space_id")
    private Long spaceId;

    @TableField("edge_id")
    private Long edgeId;

    @TableField("source_document_id")
    private Long sourceDocumentId;

    private String quote;

    private String locator;

    @TableField("extraction_method")
    private String extractionMethod;

    @TableField("created_at")
    private String createdAt;
}

package com.flevin.knowgraph.server.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 导入批次 MyBatis-Plus 持久化实体。
 */
@Data
@NoArgsConstructor
@TableName("import_batches")
public class ImportBatchEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    @TableField("space_id")
    private Long spaceId;

    private String status;

    @TableField("total_count")
    private Integer totalCount;

    @TableField("imported_count")
    private Integer importedCount;

    @TableField("duplicate_count")
    private Integer duplicateCount;

    @TableField("failed_count")
    private Integer failedCount;

    @TableField("created_at")
    private String createdAt;

    @TableField("completed_at")
    private String completedAt;
}

package com.flevin.knowgraph.server.repository.projection;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 已确认标签与有效文档计数的数据库查询投影。
 */
@Data
@NoArgsConstructor
public class KnowledgeTagSummaryProjection {

    private Long tagId;

    private String name;

    private String normalizedKey;

    private long documentCount;

    private String updatedAt;
}

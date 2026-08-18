package com.flevin.knowgraph.server.repository.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 关系证据与来源资料名称联合查询结果。
 */
@Data
@NoArgsConstructor
public class GraphEvidenceRow {

    private String id;
    private String spaceId;
    private String edgeId;
    private String sourceDocumentId;
    private String sourceDocumentName;
    private String quote;
    private String locator;
    private String extractionMethod;
    private String createdAt;
}

package com.flevin.knowgraph.server.model.tag;

import java.util.List;

/**
 * 一条已经通过 Pipeline 校验、等待原子物化的标签建议。
 *
 * @param tag 待创建或复用的标签定义
 * @param documentTag 待保存的 suggested 文档标签关系
 * @param evidences 当前标签的全部逐字证据
 */
public record DocumentTagSuggestion(
        KnowledgeTag tag,
        DocumentTag documentTag,
        List<DocumentTagEvidence> evidences
) {

    public DocumentTagSuggestion {
        evidences = List.copyOf(evidences);
    }
}

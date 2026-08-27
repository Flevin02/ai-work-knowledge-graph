package com.flevin.knowgraph.server.service.tag;

import com.flevin.knowgraph.server.model.tag.DocumentTagResponse;
import com.flevin.knowgraph.server.model.tag.DocumentTagReviewBatchRequest;
import com.flevin.knowgraph.server.model.tag.DocumentTagReviewBatchResponse;
import com.flevin.knowgraph.server.model.tag.KnowledgeTagSummaryResponse;

import java.util.List;

/**
 * 文档标签查询和人工审核应用服务。
 */
public interface DocumentTagService {

    /**
     * 查询一份来源资料的标签、证据和不可变审核历史。
     *
     * @param spaceId 知识空间标识
     * @param documentId 来源资料标识
     * @return 文档标签响应列表
     */
    List<DocumentTagResponse> listDocumentTags(
            Long spaceId,
            Long documentId
    );

    /**
     * 批量采纳或拒绝一份来源资料下的 suggested 标签。
     *
     * @param spaceId 知识空间标识
     * @param documentId 路径中的来源资料标识
     * @param request 服务端文档标签标识和审核动作
     * @return 审核统计和最新标签快照
     */
    DocumentTagReviewBatchResponse reviewDocumentTags(
            Long spaceId,
            Long documentId,
            DocumentTagReviewBatchRequest request
    );

    /**
     * 查询当前空间可参与筛选的已确认标签和有效文档数量。
     *
     * @param spaceId 知识空间标识
     * @return 已确认标签摘要列表
     */
    List<KnowledgeTagSummaryResponse> listConfirmedTags(Long spaceId);
}

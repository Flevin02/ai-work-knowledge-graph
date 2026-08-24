package com.flevin.knowgraph.server.service.association;

import com.flevin.knowgraph.server.model.association.DocumentAssociationRunResponse;
import com.flevin.knowgraph.server.model.association.DocumentRelationResponse;
import com.flevin.knowgraph.server.model.association.DocumentRelationReviewBatchRequest;
import com.flevin.knowgraph.server.model.association.DocumentRelationReviewBatchResponse;

import java.util.List;

/**
 * 文档内容关联固定 Pipeline 服务。
 */
public interface DocumentAssociationService {

    /**
     * 为当前来源资料创建并同步执行一次文档关联运行。
     *
     * @param spaceId 知识空间标识
     * @param sourceDocumentId 当前分析文档标识
     * @return 运行状态和通过服务端校验的新建议
     */
    DocumentAssociationRunResponse createRun(
            String spaceId,
            String sourceDocumentId
    );

    /**
     * 恢复指定文档关联运行及其新保存的建议。
     *
     * @param spaceId 知识空间标识
     * @param sourceDocumentId 当前分析文档标识
     * @param runId 文档关联运行标识
     * @return 运行状态和新保存的建议
     */
    DocumentAssociationRunResponse getRun(
            String spaceId,
            String sourceDocumentId,
            String runId
    );

    /**
     * 查询一份来源资料作为任一关系端点的全部文档关系。
     *
     * @param spaceId 知识空间标识
     * @param documentId 来源资料标识
     * @return 文档关系及已校验证据
     */
    List<DocumentRelationResponse> listRelations(
            String spaceId,
            String documentId
    );

    /**
     * 批量采纳或拒绝当前来源资料相关的待审核文档关系。
     *
     * @param spaceId 知识空间标识
     * @param documentId 路径中的来源资料标识
     * @param request 服务端关系标识和审核动作
     * @return 审核统计和最新关系状态
     */
    DocumentRelationReviewBatchResponse reviewRelations(
            String spaceId,
            String documentId,
            DocumentRelationReviewBatchRequest request
    );
}

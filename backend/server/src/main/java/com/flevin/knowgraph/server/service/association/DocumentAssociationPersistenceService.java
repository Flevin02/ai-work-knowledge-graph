package com.flevin.knowgraph.server.service.association;

import com.flevin.knowgraph.server.model.association.DocumentAssociationRun;
import com.flevin.knowgraph.server.model.association.DocumentRelation;
import com.flevin.knowgraph.server.model.association.DocumentRelationEvidence;
import com.flevin.knowgraph.server.model.association.DocumentRelationReview;

import java.util.List;

/**
 * 文档关联阶段 1 持久化服务。
 *
 * <p>该服务只负责文档关联运行、关系、证据和审核历史的领域边界，
 * 不调用模型、不执行候选召回，也不暴露 HTTP 接口。</p>
 */
public interface DocumentAssociationPersistenceService {

    /**
     * 保存一条文档关联运行记录，并校验主体文档和知识空间归属。
     *
     * @param run 文档关联运行领域模型
     * @return 已保存的文档关联运行
     */
    DocumentAssociationRun saveRun(DocumentAssociationRun run);

    /**
     * 保存一条文档关系候选或手工关系，并校验关系方向、文档归属和幂等键。
     *
     * @param relation 文档关系领域模型
     * @return 已保存的文档关系
     */
    DocumentRelation saveRelation(DocumentRelation relation);

    /**
     * 保存一条文档关系证据，并逐字校验原文片段属于关系两端文档。
     *
     * @param evidence 文档关系证据领域模型
     * @return 已保存的文档关系证据
     */
    DocumentRelationEvidence saveEvidence(DocumentRelationEvidence evidence);

    /**
     * 记录一条文档关系审核动作，并按状态机更新关系状态。
     *
     * @param spaceId 知识空间标识
     * @param relationId 文档关系标识
     * @param action 审核动作：accept、reject 或 create
     * @param reason 审核说明
     * @param operatorName 操作者展示名称
     * @return 已保存的审核历史记录
     */
    DocumentRelationReview reviewRelation(
            String spaceId,
            String relationId,
            String action,
            String reason,
            String operatorName
    );

    /**
     * 查询一条文档关系的全部证据。
     *
     * @param spaceId 知识空间标识
     * @param relationId 文档关系标识
     * @return 文档关系证据列表
     */
    List<DocumentRelationEvidence> listEvidence(
            String spaceId,
            String relationId
    );

    /**
     * 查询一条文档关系的不可变审核历史。
     *
     * @param spaceId 知识空间标识
     * @param relationId 文档关系标识
     * @return 审核历史列表
     */
    List<DocumentRelationReview> listReviews(
            String spaceId,
            String relationId
    );
}

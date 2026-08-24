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
     * 将处理中的文档关联运行更新为完成或失败状态。
     *
     * @param run 带最终统计、失败阶段和完成时间的运行快照
     * @return 已更新的文档关联运行
     */
    DocumentAssociationRun updateRun(DocumentAssociationRun run);

    /**
     * 查询指定文档的一次关联运行。
     *
     * @param spaceId 知识空间标识
     * @param sourceDocumentId 当前分析文档标识
     * @param runId 文档关联运行标识
     * @return 文档关联运行
     */
    DocumentAssociationRun getRun(
            String spaceId,
            String sourceDocumentId,
            String runId
    );

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
     * 在同一事务中幂等保存一条候选关系及其全部已校验证据。
     *
     * <p>相同关系键已存在时复用原关系，不重复增加建议或证据。</p>
     *
     * @param relation 待保存的候选关系；关系键可为空，由服务端计算
     * @param evidences 与候选关系一起原子保存的证据
     * @return 新保存或复用的文档关系
     */
    DocumentRelation saveSuggestion(
            DocumentRelation relation,
            List<DocumentRelationEvidence> evidences
    );

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

    /**
     * 查询指定空间和标识的文档关系。
     *
     * @param spaceId 知识空间标识
     * @param relationId 文档关系标识
     * @return 文档关系
     */
    DocumentRelation getRelation(
            String spaceId,
            String relationId
    );

    /**
     * 查询一次运行新保存的全部文档关系。
     *
     * @param spaceId 知识空间标识
     * @param runId 文档关联运行标识
     * @return 运行关系列表
     */
    List<DocumentRelation> listRelationsByRun(
            String spaceId,
            String runId
    );

    /**
     * 查询一份来源资料作为任一端点的全部文档关系。
     *
     * @param spaceId 知识空间标识
     * @param documentId 来源资料标识
     * @return 当前资料相关的关系列表
     */
    List<DocumentRelation> listRelationsByDocument(
            String spaceId,
            String documentId
    );

    /**
     * 批量查询多条文档关系的证据，供 API 避免逐关系查询。
     *
     * @param spaceId 知识空间标识
     * @param relationIds 文档关系标识
     * @return 所有匹配关系的证据
     */
    List<DocumentRelationEvidence> listEvidence(
            String spaceId,
            List<String> relationIds
    );
}

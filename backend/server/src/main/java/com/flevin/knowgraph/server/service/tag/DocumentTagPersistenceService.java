package com.flevin.knowgraph.server.service.tag;

import com.flevin.knowgraph.server.model.tag.DocumentTag;
import com.flevin.knowgraph.server.model.tag.DocumentTagEvidence;
import com.flevin.knowgraph.server.model.tag.DocumentTagReview;
import com.flevin.knowgraph.server.model.tag.DocumentTagSuggestion;
import com.flevin.knowgraph.server.model.tag.KnowledgeTag;

import java.util.List;

/**
 * 可选标签阶段的持久化领域服务。
 *
 * <p>该服务只维护标签定义、文档标签关系、来源状态、幂等、证据和不可变审核历史边界，
 * 不调用模型，也不暴露 HTTP 接口。</p>
 */
public interface DocumentTagPersistenceService {

    /**
     * 在同一事务中幂等保存一条 AI 候选标签及其全部逐字证据。
     *
     * @param tag 待创建或按规范化键复用的空间标签定义
     * @param documentTag 初始状态必须为 suggested 的 AI 文档标签关系
     * @param evidences 当前来源资料中能够逐字反查的标签证据
     * @return 新保存或按冻结幂等键复用的文档标签关系
     */
    DocumentTag saveAiSuggestion(
            KnowledgeTag tag,
            DocumentTag documentTag,
            List<DocumentTagEvidence> evidences
    );

    /**
     * 在一个事务中幂等保存一次标签运行的全部 AI 候选。
     *
     * @param suggestions 已通过 Pipeline 引用和分片校验的标签建议
     * @return 按输入顺序返回新保存或复用的文档标签关系
     */
    List<DocumentTag> saveAiSuggestions(List<DocumentTagSuggestion> suggestions);

    /**
     * 幂等保存用户手工标签，文档标签关系保存后直接为 confirmed。
     *
     * @param tag 待创建或按规范化键复用的空间标签定义
     * @param documentTag 来源必须为 user、初始状态必须为 confirmed 的文档标签关系
     * @return 新保存或按手工标签幂等键复用的文档标签关系
     */
    DocumentTag saveUserTag(
            KnowledgeTag tag,
            DocumentTag documentTag
    );

    /**
     * 将一条 suggested 文档标签迁移为 confirmed 或 rejected，并追加不可变审核历史。
     *
     * @param spaceId 知识空间标识
     * @param documentTagId 文档标签关系标识
     * @param action 审核动作：accept 或 reject
     * @param reason 可选审核说明
     * @param operatorName 操作者展示名称
     * @return 已保存的不可变审核历史
     */
    DocumentTagReview reviewDocumentTag(
            String spaceId,
            String documentTagId,
            String action,
            String reason,
            String operatorName
    );

    /**
     * 查询指定来源资料的全部文档标签状态，用于后续审核和页面恢复。
     *
     * @param spaceId 知识空间标识
     * @param sourceDocumentId 来源资料标识
     * @return 文档标签关系列表
     */
    List<DocumentTag> listDocumentTags(
            String spaceId,
            String sourceDocumentId
    );

    /**
     * 查询一条文档标签关系的全部逐字证据。
     *
     * @param spaceId 知识空间标识
     * @param documentTagId 文档标签关系标识
     * @return 标签证据列表
     */
    List<DocumentTagEvidence> listEvidence(
            String spaceId,
            String documentTagId
    );
}

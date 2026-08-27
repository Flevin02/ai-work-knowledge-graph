package com.flevin.knowgraph.server.service.association;

import com.flevin.knowgraph.server.model.association.DocumentCandidateRecall;

/**
 * 文档关联第一版无 Embedding 候选召回服务。
 *
 * <p>服务只负责根据当前知识空间内的有效来源资料生成可解释候选，
 * 不调用模型、不创建文档关系，也不把候选直接视为正式事实。</p>
 */
public interface DocumentCandidateRecallService {

    /** 默认无标签内容候选召回策略版本。 */
    String CONTENT_POLICY_VERSION = "document-candidate-recall-v1";

    /** 用户显式开启 confirmed 标签补充后的候选召回策略版本。 */
    String CONFIRMED_TAG_AUGMENTATION_POLICY_VERSION = "document-candidate-recall-v2";

    /** 共同 confirmed 标签达到分层阈值后的候选召回策略版本。 */
    String CONFIRMED_TAG_THRESHOLD_POLICY_VERSION = "document-candidate-recall-v3";

    /** 标签-only 候选必须共享的 confirmed 标签最小数量。 */
    int MIN_CONFIRMED_TAG_MATCHES = 2;

    /**
     * 按冻结的 TopK=8 规则召回当前文档的关联候选。
     *
     * @param spaceId 知识空间标识
     * @param sourceDocumentId 当前作为召回主体的来源资料标识
     * @return 无 Embedding 候选召回结果；没有命中时返回空候选列表
     */
    DocumentCandidateRecall recall(
            Long spaceId,
            Long sourceDocumentId
    );

    /**
     * 按冻结策略召回当前文档的关联候选，并允许测试或后续受控调用缩小 TopK。
     *
     * @param spaceId 知识空间标识
     * @param sourceDocumentId 当前作为召回主体的来源资料标识
     * @param topK 候选数量上限，取值范围为 1 到 8
     * @return 无 Embedding 候选召回结果；没有命中时返回空候选列表
     */
    DocumentCandidateRecall recall(
            Long spaceId,
            Long sourceDocumentId,
            int topK
    );

    /**
     * 按冻结内容通道召回候选，并允许用户显式开启已确认标签补充通道。
     *
     * @param spaceId 知识空间标识
     * @param sourceDocumentId 当前作为召回主体的来源资料标识
     * @param topK 候选数量上限，取值范围为 1 到 8
     * @param includeConfirmedTags 是否读取当前空间已确认标签作为补充召回条件
     * @return 无 Embedding 候选召回结果
     */
    DocumentCandidateRecall recall(
            Long spaceId,
            Long sourceDocumentId,
            int topK,
            boolean includeConfirmedTags
    );
}

package com.flevin.knowgraph.server.service.ai;

import com.flevin.knowgraph.server.model.ai.AiDocumentExtractionResponse;
import com.flevin.knowgraph.server.model.ai.AiExtractionRunDetail;
import com.flevin.knowgraph.server.model.ai.AiExtractionRunSummary;
import com.flevin.knowgraph.server.model.ai.AiRelationReviewRequest;
import com.flevin.knowgraph.server.model.ai.AiRelationReviewResponse;
import com.flevin.knowgraph.server.model.ai.AiRelationReviewState;

import java.util.List;

/**
 * 来源资料 AI 抽取编排服务，负责解析、分片、模型调用、候选物化和关系审核。
 */
public interface AiExtractionService {

    /**
     * 对已导入来源资料执行结构化抽取，并保存待审核候选图谱事实。
     *
     * @param spaceId 知识空间标识
     * @param documentId 来源资料标识
     * @return 按来源分片组织的结构化候选结果
     */
    AiDocumentExtractionResponse extractDocument(
            String spaceId,
            String documentId
    );

    /**
     * 对已导入来源资料执行流式结构化抽取预览，并发布真实运行阶段和模型增量。
     *
     * <p>失败会通过 {@code error} 事件结束，不向传输层继续抛出；部分模型文本不会写入正式图谱或完整结果字段。</p>
     *
     * @param spaceId 知识空间标识
     * @param documentId 来源资料标识
     * @param eventPublisher 抽取运行事件发布器
     */
    void streamDocument(
            String spaceId,
            String documentId,
            AiExtractionEventPublisher eventPublisher
    );

    /**
     * 查询来源资料的历史 AI 抽取记录摘要。
     *
     * @param spaceId 知识空间标识
     * @param documentId 来源资料标识
     * @return 最新记录优先的抽取运行摘要
     */
    List<AiExtractionRunSummary> listExtractions(
            String spaceId,
            String documentId
    );

    /**
     * 查询来源资料的单次 AI 抽取结果。
     *
     * @param spaceId 知识空间标识
     * @param documentId 来源资料标识
     * @param extractionId 抽取运行标识
     * @return 抽取运行摘要和完整结果
     */
    AiExtractionRunDetail getExtraction(
            String spaceId,
            String documentId,
            String extractionId
    );

    /**
     * 审核指定抽取运行中的一批候选关系，并写入图谱状态和审核历史。
     *
     * @param spaceId 知识空间标识
     * @param documentId 来源资料标识
     * @param extractionId 抽取运行标识
     * @param request 批量审核决定
     * @return 本次审核统计和当前剩余待审核数量
     */
    AiRelationReviewResponse reviewRelations(
            String spaceId,
            String documentId,
            String extractionId,
            AiRelationReviewRequest request
    );

    /**
     * 查询指定抽取运行已经持久化的候选关系审核状态。
     *
     * @param spaceId 知识空间标识
     * @param documentId 来源资料标识
     * @param extractionId 抽取运行标识
     * @return 已审核候选关系状态
     */
    List<AiRelationReviewState> listReviewStates(
            String spaceId,
            String documentId,
            String extractionId
    );
}

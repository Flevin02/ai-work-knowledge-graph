package com.flevin.knowgraph.server.service.ai;

import com.flevin.knowgraph.server.model.ai.AiDocumentExtractionResponse;
import com.flevin.knowgraph.server.model.ai.AiExtractionRunDetail;
import com.flevin.knowgraph.server.model.ai.AiExtractionRunSummary;

import java.util.List;

/**
 * 来源资料 AI 抽取编排服务，负责解析、分片、模型调用和结果预览。
 */
public interface AiExtractionService {

    /**
     * 对已导入来源资料执行结构化抽取预览，不直接写入正式图谱。
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
}

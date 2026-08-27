package com.flevin.knowgraph.server.service.tag;

import com.flevin.knowgraph.server.model.tag.DocumentTaggingRunResponse;

/**
 * 文档标签抽取固定 Pipeline 服务。
 */
public interface DocumentTaggingService {

    /**
     * 为当前来源资料创建并同步执行一次标签运行。
     *
     * @param spaceId 知识空间标识
     * @param sourceDocumentId 当前来源资料标识
     * @return 运行状态和本次新保存的标签建议
     */
    DocumentTaggingRunResponse createRun(
            Long spaceId,
            Long sourceDocumentId
    );

    /**
     * 恢复指定文档最近创建的一次标签运行。
     *
     * @param spaceId 知识空间标识
     * @param sourceDocumentId 当前来源资料标识
     * @return 最近一次运行状态和建议
     */
    DocumentTaggingRunResponse getLatestRun(
            Long spaceId,
            Long sourceDocumentId
    );

    /**
     * 恢复指定文档的一次标签运行及其新保存建议。
     *
     * @param spaceId 知识空间标识
     * @param sourceDocumentId 当前来源资料标识
     * @param runId 标签运行标识
     * @return 可重复恢复的运行状态和建议
     */
    DocumentTaggingRunResponse getRun(
            Long spaceId,
            Long sourceDocumentId,
            Long runId
    );
}

package com.flevin.knowgraph.server.model.ai;

import jakarta.validation.constraints.NotBlank;

/**
 * 单个来源分片的结构化抽取请求。
 *
 * @param sourceDocumentId 来源资料标识
 * @param documentName 来源资料展示名称
 * @param documentType 文档业务类型
 * @param chunkId 当前文本分片标识
 * @param sectionPath 当前章节路径
 * @param content 当前可引用的原文内容
 */
public record AiExtractionRequest(
        @NotBlank String sourceDocumentId,
        @NotBlank String documentName,
        @NotBlank String documentType,
        @NotBlank String chunkId,
        @NotBlank String sectionPath,
        @NotBlank String content
) {
}

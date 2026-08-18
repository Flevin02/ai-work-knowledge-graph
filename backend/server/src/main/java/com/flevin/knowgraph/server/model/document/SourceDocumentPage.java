package com.flevin.knowgraph.server.model.document;

import java.util.List;

/**
 * 来源资料领域分页结果，隔离 Service 与 MyBatis-Plus 分页对象。
 *
 * @param items 当前页来源资料
 * @param page 当前请求页码
 * @param pageSize 每页数量
 * @param total 有效来源资料总数
 * @param totalPages 总页数
 */
public record SourceDocumentPage(
        List<SourceDocument> items,
        int page,
        int pageSize,
        long total,
        long totalPages
) {
}

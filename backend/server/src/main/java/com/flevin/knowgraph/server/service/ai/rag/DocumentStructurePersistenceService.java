package com.flevin.knowgraph.server.service.ai.rag;

import com.flevin.knowgraph.server.model.ai.rag.DocumentChunk;
import com.flevin.knowgraph.server.model.ai.rag.DocumentSection;
import com.flevin.knowgraph.server.model.ai.rag.PersistedDocumentStructure;
import com.flevin.knowgraph.server.model.document.SourceDocument;

import java.util.List;

/**
 * 章节与分片事实持久化服务，负责原文反查、版本隔离和重复运行幂等。
 */
public interface DocumentStructurePersistenceService {

    /**
     * 校验确定性解析结果并持久化当前版本的章节和分片事实。
     *
     * @param document 已确认属于当前知识空间的来源资料
     * @param sections 按来源原文顺序排列的章节
     * @param chunks 按来源原文顺序排列的章节感知分片
     * @return 已持久化且带数据库事实标识的结构快照
     */
    PersistedDocumentStructure persist(
            SourceDocument document,
            List<DocumentSection> sections,
            List<DocumentChunk> chunks
    );
}

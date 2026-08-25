package com.flevin.knowgraph.server.service.documentgraph.impl;

import com.flevin.knowgraph.server.model.association.DocumentRelation;
import com.flevin.knowgraph.server.model.document.SourceDocument;
import com.flevin.knowgraph.server.model.documentgraph.DocumentGraphEdgeResponse;
import com.flevin.knowgraph.server.model.documentgraph.DocumentGraphNodeResponse;
import com.flevin.knowgraph.server.model.documentgraph.DocumentGraphResponse;
import com.flevin.knowgraph.server.repository.association.DocumentRelationRepository;
import com.flevin.knowgraph.server.repository.document.SourceDocumentRepository;
import com.flevin.knowgraph.server.service.documentgraph.DocumentGraphService;
import com.flevin.knowgraph.server.service.space.KnowledgeSpaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 独立文档关系图查询服务实现。
 */
@Service
@RequiredArgsConstructor
public class DocumentGraphServiceImpl implements DocumentGraphService {

    private static final String CONFIRMED_STATUS = "confirmed";

    private final KnowledgeSpaceService knowledgeSpaceService;
    private final SourceDocumentRepository sourceDocumentRepository;
    private final DocumentRelationRepository documentRelationRepository;

    /**
     * 查询真实来源文档和已确认关系，过滤已经软删除的关系端点。
     *
     * @param spaceId 知识空间标识
     * @return 当前知识空间的独立文档关系图
     */
    @Override
    public DocumentGraphResponse getGraph(String spaceId) {
        // 校验知识空间当前有效，阻断跨空间读取文档关系图
        knowledgeSpaceService.requireActive(spaceId);

        // 批量查询当前空间全部有效来源文档，节点不从模型自由创建
        List<SourceDocument> documents = sourceDocumentRepository.findAll(spaceId);
        Map<String, SourceDocument> documentById = documents.stream()
                .collect(Collectors.toMap(SourceDocument::id, Function.identity()));

        // 只查询已确认文档关系，默认不把 suggested/rejected/stale 展示为正式图边
        List<DocumentRelation> relations = documentRelationRepository
                .findAllBySpaceAndStatus(spaceId, CONFIRMED_STATUS)
                .stream()
                .filter(relation -> documentById.containsKey(relation.sourceDocumentId()))
                .filter(relation -> documentById.containsKey(relation.targetDocumentId()))
                .toList();

        // 将真实来源文档映射为前端节点，摘要缺失时回退导入 excerpt
        List<DocumentGraphNodeResponse> nodes = documents.stream()
                .map(document -> new DocumentGraphNodeResponse(
                        document.id(),
                        document.name(),
                        document.kind(),
                        document.documentType().getValue(),
                        document.excerpt(),
                        document.status(),
                        document.updatedAt()
                ))
                .toList();

        // 将已确认文档关系映射为独立图边，不复用实体图谱的 graph_edges
        List<DocumentGraphEdgeResponse> edges = relations.stream()
                .map(relation -> new DocumentGraphEdgeResponse(
                        relation.id(),
                        relation.sourceDocumentId(),
                        relation.targetDocumentId(),
                        relation.relationType(),
                        relation.direction(),
                        relation.status(),
                        relation.confidence(),
                        relation.reason(),
                        relation.updatedAt()
                ))
                .toList();
        return new DocumentGraphResponse(nodes, edges);
    }
}

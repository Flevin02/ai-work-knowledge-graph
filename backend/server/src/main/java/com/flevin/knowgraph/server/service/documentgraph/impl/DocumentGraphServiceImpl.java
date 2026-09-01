package com.flevin.knowgraph.server.service.documentgraph.impl;

import com.flevin.knowgraph.server.model.association.DocumentRelation;
import com.flevin.knowgraph.server.model.association.DocumentRelationEvidence;
import com.flevin.knowgraph.server.model.document.SourceDocument;
import com.flevin.knowgraph.server.model.documentgraph.DocumentGraphEdgeResponse;
import com.flevin.knowgraph.server.model.documentgraph.DocumentGraphNodeResponse;
import com.flevin.knowgraph.server.model.documentgraph.DocumentGraphResponse;
import com.flevin.knowgraph.server.repository.association.DocumentRelationRepository;
import com.flevin.knowgraph.server.repository.association.DocumentRelationEvidenceRepository;
import com.flevin.knowgraph.server.repository.document.SourceDocumentRepository;
import com.flevin.knowgraph.server.repository.tag.DocumentTagRepository;
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
    private final DocumentRelationEvidenceRepository documentRelationEvidenceRepository;
    private final DocumentTagRepository documentTagRepository;

    /**
     * 查询真实来源文档和已确认关系，过滤已经软删除的关系端点。
     *
     * @param spaceId 知识空间标识
     * @param tagId 可选的 confirmed 标签定义标识；提供时只返回含该标签的文档节点与关系
     * @return 当前知识空间的独立文档关系图
     */
    @Override
    public DocumentGraphResponse getGraph(Long spaceId, Long tagId) {
        // 校验知识空间当前有效，阻断跨空间读取文档关系图
        knowledgeSpaceService.requireActive(spaceId);

        // 批量查询当前空间全部有效来源文档，节点不从模型自由创建
        List<SourceDocument> documents = sourceDocumentRepository.findAll(spaceId);

        // 标签筛选：节点限定为含该 confirmed 标签的文档，边随后只保留两端均在集合内的关系
        if (tagId != null) {
            java.util.Set<Long> documentIdsByTag = java.util.Set.copyOf(
                    documentTagRepository.findConfirmedDocumentIdsByTag(spaceId, tagId)
            );
            documents = documents.stream()
                    .filter(document -> documentIdsByTag.contains(document.id()))
                    .toList();
        }
        Map<Long, SourceDocument> documentById = documents.stream()
                .collect(Collectors.toMap(SourceDocument::id, Function.identity()));

        // 只查询已确认文档关系，默认不把 suggested/rejected/stale 展示为正式图边
        List<DocumentRelation> relations = documentRelationRepository
                .findAllBySpaceAndStatus(spaceId, CONFIRMED_STATUS)
                .stream()
                .filter(relation -> documentById.containsKey(relation.sourceDocumentId()))
                .filter(relation -> documentById.containsKey(relation.targetDocumentId()))
                .toList();
        Map<Long, List<DocumentRelationEvidence>> evidenceByRelationId = documentRelationEvidenceRepository
                .findAllByRelations(spaceId, relations.stream().map(DocumentRelation::id).toList())
                .stream()
                .collect(Collectors.groupingBy(
                        DocumentRelationEvidence::documentRelationId,
                        Collectors.toList()
                ));

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
                        evidenceByRelationId.getOrDefault(relation.id(), List.of()).stream()
                                .map(evidence -> new DocumentGraphEdgeResponse.Evidence(
                                        evidence.id(),
                                        evidence.sourceDocumentId(),
                                        evidence.chunkId(),
                                        evidence.sectionPath(),
                                        evidence.quote(),
                                        evidence.startOffset(),
                                        evidence.endOffset(),
                                        evidence.evidenceRole()
                                ))
                                .toList(),
                        relation.updatedAt()
                ))
                .toList();
        return new DocumentGraphResponse(nodes, edges);
    }
}

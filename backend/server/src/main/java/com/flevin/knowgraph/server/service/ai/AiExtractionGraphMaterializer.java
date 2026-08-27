package com.flevin.knowgraph.server.service.ai;

import com.flevin.knowgraph.server.model.ai.AiChunkExtractionResult;
import com.flevin.knowgraph.server.model.ai.AiDocumentExtractionResponse;
import com.flevin.knowgraph.server.model.ai.AiEntityCandidate;
import com.flevin.knowgraph.server.model.ai.AiRelationCandidate;
import com.flevin.knowgraph.server.model.ai.AiRelationReviewAction;
import com.flevin.knowgraph.server.model.ai.AiRelationReviewItem;
import com.flevin.knowgraph.server.model.ai.AiRelationReviewRequest;
import com.flevin.knowgraph.server.model.ai.AiRelationReviewResponse;
import com.flevin.knowgraph.server.model.ai.AiRelationReviewState;
import com.flevin.knowgraph.server.model.document.SourceDocument;
import com.flevin.knowgraph.common.enums.ErrorCode;
import com.flevin.knowgraph.common.exception.TipsException;
import com.flevin.knowgraph.server.model.graph.GraphEdge;
import com.flevin.knowgraph.server.model.graph.GraphEvidence;
import com.flevin.knowgraph.server.model.graph.GraphNode;
import com.flevin.knowgraph.server.repository.graph.GraphRepository;
import com.flevin.knowgraph.server.repository.graph.ReviewActionRepository;
import com.flevin.knowgraph.server.repository.entity.ReviewActionEntity;
import com.flevin.knowgraph.server.util.SnowflakeIdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 将已经通过结构和证据校验的 AI 候选结果物化为可审核的图谱事实。
 *
 * <p>节点和证据使用确定性标识，重复抽取不会重复创建同一实体、关系或证据；
 * 关系首次进入图谱时始终保持 {@code suggested}，人工审核前不能成为正式关系。</p>
 */
@Service
@RequiredArgsConstructor
public class AiExtractionGraphMaterializer {

    private final GraphRepository graphRepository;
    private final ReviewActionRepository reviewActionRepository;

    /**
     * 将一份已校验的完整抽取结果写入当前知识空间。
     *
     * @param spaceId 知识空间标识
     * @param document 当前来源资料
     * @param extraction 完整抽取结果
     */
    @Transactional
    public void materialize(
            Long spaceId,
            SourceDocument document,
            AiDocumentExtractionResponse extraction
    ) {
        Map<String, AiEntityCandidate> candidatesByKey = collectEntityCandidates(extraction);
        Map<String, GraphNode> nodesByKey = loadOrCreateNodes(spaceId, document, candidatesByKey);

        // 按分片保留候选标识局部作用域，避免不同分片都使用 entity-1 时互相覆盖
        extraction.chunks().forEach(chunk -> materializeChunk(
                spaceId,
                document,
                extraction.extractionId(),
                chunk,
                nodesByKey
        ));
    }

    /**
     * 校验并保存一批候选关系审核决定。
     *
     * @param spaceId 知识空间标识
     * @param document 当前来源资料
     * @param extraction 完整抽取结果
     * @param request 审核请求
     * @return 本次动作和剩余待审核数量
     */
    @Transactional
    public AiRelationReviewResponse reviewRelations(
            Long spaceId,
            SourceDocument document,
            AiDocumentExtractionResponse extraction,
            AiRelationReviewRequest request
    ) {
        Map<String, AiEntityCandidate> candidatesByKey = collectEntityCandidates(extraction);
        Map<String, GraphNode> nodesByKey = loadOrCreateNodes(spaceId, document, candidatesByKey);
        int acceptedCount = 0;
        int rejectedCount = 0;

        for (AiRelationReviewItem item : request.reviews()) {
            AiChunkExtractionResult chunk = findChunk(extraction, item.chunkId());
            AiRelationCandidate relation = findRelation(chunk, item.relationIndex());
            GraphNode sourceNode = findCandidateNode(chunk, relation.sourceEntityId(), nodesByKey);
            GraphNode targetNode = findCandidateNode(chunk, relation.targetEntityId(), nodesByKey);
            if (sourceNode == null || targetNode == null) {
                throw new TipsException(ErrorCode.PARAM_ERROR, "候选关系引用的实体不存在");
            }

            GraphEdge edge = graphRepository.findEdgeBySignature(
                            spaceId,
                            sourceNode.id(),
                            targetNode.id(),
                            relation.relationType()
                    )
                    .orElseThrow(() -> new TipsException(ErrorCode.NOT_FOUND, "候选关系尚未写入图谱"));
            String status = item.action() == AiRelationReviewAction.ACCEPT ? "confirmed" : "rejected";
            String action = item.action() == AiRelationReviewAction.ACCEPT ? "accept" : "reject";

            // 更新关系正式状态，保持同一关系的主体、客体和证据不变
            graphRepository.updateEdgeStatus(edge.id(), status, Instant.now());

            // 保存不可变审核历史，批量审核中的每个决定都可追溯
            reviewActionRepository.save(toReviewAction(
                    spaceId,
                    edge.id(),
                    action,
                    request.operatorName(),
                    item.reason()
            ));
            if (item.action() == AiRelationReviewAction.ACCEPT) {
                acceptedCount++;
            } else {
                rejectedCount++;
            }
        }

        int pendingCount = countPendingRelations(spaceId, extraction, nodesByKey);
        return new AiRelationReviewResponse(acceptedCount, rejectedCount, pendingCount);
    }

    /**
     * 根据当前图谱关系状态恢复一份抽取结果的审核决定。
     *
     * @param spaceId 知识空间标识
     * @param extraction 完整抽取结果
     * @return 已审核关系状态
     */
    @Transactional(readOnly = true)
    public List<AiRelationReviewState> listReviewStates(
            Long spaceId,
            AiDocumentExtractionResponse extraction
    ) {
        Map<String, AiEntityCandidate> candidatesByKey = collectEntityCandidates(extraction);
        Map<String, GraphNode> nodesByKey = graphRepository.findNodesByNormalizedKeys(
                        spaceId,
                        new ArrayList<>(candidatesByKey.keySet())
                ).stream()
                .collect(java.util.stream.Collectors.toMap(GraphNode::normalizedKey, node -> node));
        List<AiRelationReviewState> states = new ArrayList<>();

        for (AiChunkExtractionResult chunk : extraction.chunks()) {
            for (int index = 0; index < chunk.extraction().relations().size(); index++) {
                int relationIndex = index;
                AiRelationCandidate relation = chunk.extraction().relations().get(index);
                GraphNode sourceNode = findCandidateNode(chunk, relation.sourceEntityId(), nodesByKey);
                GraphNode targetNode = findCandidateNode(chunk, relation.targetEntityId(), nodesByKey);
                if (sourceNode == null || targetNode == null) {
                    continue;
                }
                graphRepository.findEdgeBySignature(spaceId, sourceNode.id(), targetNode.id(), relation.relationType())
                        .map(edge -> toReviewState(chunk.chunkId(), relationIndex, edge.status()))
                        .ifPresent(states::add);
            }
        }
        return states;
    }

    private Map<String, AiEntityCandidate> collectEntityCandidates(AiDocumentExtractionResponse extraction) {
        Map<String, AiEntityCandidate> candidatesByKey = new LinkedHashMap<>();
        extraction.chunks().forEach(chunk -> chunk.extraction().entities().forEach(candidate ->
                candidatesByKey.putIfAbsent(normalizedKey(candidate), candidate)
        ));
        return candidatesByKey;
    }

    private Map<String, GraphNode> loadOrCreateNodes(
            Long spaceId,
            SourceDocument document,
            Map<String, AiEntityCandidate> candidatesByKey
    ) {
        List<String> normalizedKeys = new ArrayList<>(candidatesByKey.keySet());
        Map<String, GraphNode> nodesByKey = new HashMap<>();

        // 批量读取可复用节点，避免按 AI 实体逐条查询数据库
        graphRepository.findNodesByNormalizedKeys(spaceId, normalizedKeys)
                .forEach(node -> nodesByKey.put(node.normalizedKey(), node));

        candidatesByKey.forEach((key, candidate) -> {
            GraphNode existingNode = nodesByKey.get(key);
            if (existingNode == null) {
                GraphNode createdNode = createNode(spaceId, document, candidate, key);

                // 保存候选实体索引，后续关系审核仍以 suggested 边为准
                graphRepository.saveNode(createdNode);
                nodesByKey.put(key, createdNode);
                return;
            }

            GraphNode mergedNode = mergeNodeSource(existingNode, document.id(), candidate);
            if (!mergedNode.equals(existingNode)) {
                // 更新已存在节点的来源贡献和可解释摘要，保留节点唯一标识
                graphRepository.updateNode(mergedNode);
                nodesByKey.put(key, mergedNode);
            }
        });
        return nodesByKey;
    }

    private GraphNode createNode(
            Long spaceId,
            SourceDocument document,
            AiEntityCandidate candidate,
            String normalizedKey
    ) {
        Instant now = Instant.now();
        return new GraphNode(
                deterministicId("node", spaceId, normalizedKey),
                spaceId,
                candidate.type().name().toLowerCase(Locale.ROOT),
                candidate.name().strip(),
                candidate.summary() == null || candidate.summary().isBlank()
                        ? candidate.name().strip()
                        : candidate.summary().strip(),
                "active",
                normalizedKey,
                List.of(document.id()),
                now,
                now
        );
    }

    private GraphNode mergeNodeSource(
            GraphNode existingNode,
            Long documentId,
            AiEntityCandidate candidate
    ) {
        List<Long> sourceIds = new ArrayList<>(existingNode.sourceIds());
        if (!sourceIds.contains(documentId)) {
            sourceIds.add(documentId);
        }

        String summary = existingNode.summary();
        if ((summary == null || summary.isBlank()) && candidate.summary() != null) {
            summary = candidate.summary().strip();
        }

        String status = "stale".equals(existingNode.status()) ? "active" : existingNode.status();
        return new GraphNode(
                existingNode.id(),
                existingNode.spaceId(),
                existingNode.type(),
                existingNode.label(),
                summary,
                status,
                existingNode.normalizedKey(),
                List.copyOf(sourceIds),
                existingNode.createdAt(),
                sourceIds.equals(existingNode.sourceIds()) && status.equals(existingNode.status())
                        ? existingNode.updatedAt()
                        : Instant.now()
        );
    }

    private void materializeChunk(
            Long spaceId,
            SourceDocument document,
            Long extractionId,
            AiChunkExtractionResult chunk,
            Map<String, GraphNode> nodesByKey
    ) {
        Map<String, GraphNode> nodesByCandidateId = new HashMap<>();
        chunk.extraction().entities().forEach(candidate -> nodesByCandidateId.put(
                candidate.candidateId(),
                nodesByKey.get(normalizedKey(candidate))
        ));

        Map<String, com.flevin.knowgraph.server.model.ai.AiEvidenceCandidate> evidencesById = chunk.extraction()
                .evidences().stream()
                .collect(java.util.stream.Collectors.toMap(
                        evidence -> evidence.evidenceId(),
                        evidence -> evidence,
                        (left, right) -> left
                ));

        for (int index = 0; index < chunk.extraction().relations().size(); index++) {
            AiRelationCandidate relation = chunk.extraction().relations().get(index);
            GraphNode sourceNode = nodesByCandidateId.get(relation.sourceEntityId());
            GraphNode targetNode = nodesByCandidateId.get(relation.targetEntityId());
            if (sourceNode == null || targetNode == null) {
                continue;
            }

            Long edgeId = deterministicId(
                    "edge",
                    spaceId,
                    sourceNode.id(),
                    relation.relationType(),
                    targetNode.id()
            );
            GraphEdge edge = graphRepository.findEdgeBySignature(
                            spaceId,
                            sourceNode.id(),
                            targetNode.id(),
                            relation.relationType()
                    )
                    .orElseGet(() -> {
                Instant now = Instant.now();
                GraphEdge createdEdge = new GraphEdge(
                        edgeId,
                        spaceId,
                        sourceNode.id(),
                        targetNode.id(),
                        relation.relationType(),
                        "suggested",
                        relation.confidence(),
                        now,
                        now
                );

                // 新候选关系必须先进入 suggested，不能绕过人工审核成为正式关系
                graphRepository.saveEdge(createdEdge);
                return createdEdge;
                    });

            // 重新抽取后为同一关系补充新的原文证据，证据本身保持幂等
            relation.evidenceIds().forEach(evidenceId -> {
                var evidence = evidencesById.get(evidenceId);
                if (evidence == null || !document.id().equals(evidence.sourceDocumentId())) {
                    return;
                }
                Long graphEvidenceId = deterministicId(
                        "evidence",
                        edge.id(),
                        evidence.sourceDocumentId(),
                        evidence.sectionPath(),
                        evidence.quote()
                );
                if (graphRepository.existsEvidence(graphEvidenceId)) {
                    return;
                }

                // 保存逐字证据和章节定位，关系审核时仍可回到原文上下文
                graphRepository.saveEvidence(new GraphEvidence(
                        graphEvidenceId,
                        spaceId,
                        edge.id(),
                        evidence.sourceDocumentId(),
                        document.name(),
                        evidence.quote(),
                        evidence.sectionPath(),
                        "ai",
                        Instant.now()
                ));
            });
        }
    }

    private AiChunkExtractionResult findChunk(
            AiDocumentExtractionResponse extraction,
            String chunkId
    ) {
        return extraction.chunks().stream()
                .filter(chunk -> chunk.chunkId().equals(chunkId))
                .findFirst()
                .orElseThrow(() -> new TipsException(ErrorCode.PARAM_ERROR, "候选关系分片不存在"));
    }

    private AiRelationCandidate findRelation(
            AiChunkExtractionResult chunk,
            int relationIndex
    ) {
        if (relationIndex < 0 || relationIndex >= chunk.extraction().relations().size()) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "候选关系顺序不存在");
        }
        return chunk.extraction().relations().get(relationIndex);
    }

    private GraphNode findCandidateNode(
            AiChunkExtractionResult chunk,
            String candidateId,
            Map<String, GraphNode> nodesByKey
    ) {
        return chunk.extraction().entities().stream()
                .filter(candidate -> candidate.candidateId().equals(candidateId))
                .map(candidate -> nodesByKey.get(normalizedKey(candidate)))
                .findFirst()
                .orElse(null);
    }

    private int countPendingRelations(
            Long spaceId,
            AiDocumentExtractionResponse extraction,
            Map<String, GraphNode> nodesByKey
    ) {
        int pendingCount = 0;
        for (AiChunkExtractionResult chunk : extraction.chunks()) {
            for (AiRelationCandidate relation : chunk.extraction().relations()) {
                GraphNode sourceNode = findCandidateNode(chunk, relation.sourceEntityId(), nodesByKey);
                GraphNode targetNode = findCandidateNode(chunk, relation.targetEntityId(), nodesByKey);
                if (sourceNode == null || targetNode == null) {
                    continue;
                }
                boolean pending = graphRepository.findEdgeBySignature(
                                spaceId,
                                sourceNode.id(),
                                targetNode.id(),
                                relation.relationType()
                        )
                        .map(edge -> "suggested".equals(edge.status()))
                        .orElse(false);
                if (pending) {
                    pendingCount++;
                }
            }
        }
        return pendingCount;
    }

    private ReviewActionEntity toReviewAction(
            Long spaceId,
            Long edgeId,
            String action,
            String operatorName,
            String reason
    ) {
        ReviewActionEntity entity = new ReviewActionEntity();
        entity.setId(SnowflakeIdGenerator.nextId());
        entity.setSpaceId(spaceId);
        entity.setEdgeId(edgeId);
        entity.setAction(action);
        entity.setReason(reason == null || reason.isBlank() ? null : reason.strip());
        entity.setOperatorName(operatorName == null || operatorName.isBlank() ? "local-user" : operatorName.strip());
        entity.setCreatedAt(Instant.now().toString());
        return entity;
    }

    private AiRelationReviewState toReviewState(
            String chunkId,
            int relationIndex,
            String edgeStatus
    ) {
        if ("confirmed".equals(edgeStatus)) {
            return new AiRelationReviewState(chunkId, relationIndex, AiRelationReviewAction.ACCEPT);
        }
        if ("rejected".equals(edgeStatus)) {
            return new AiRelationReviewState(chunkId, relationIndex, AiRelationReviewAction.REJECT);
        }
        return null;
    }

    private String normalizedKey(AiEntityCandidate candidate) {
        return candidate.type().name().toLowerCase(Locale.ROOT) + ":" + normalizeName(candidate.name());
    }

    private String normalizeName(String name) {
        return name.replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
    }

    private Long deterministicId(String prefix, Object... parts) {
        String source = prefix + ":" + java.util.Arrays.stream(parts)
                .map(String::valueOf)
                .collect(java.util.stream.Collectors.joining("|"));
        return SnowflakeIdGenerator.stableId(source);
    }
}

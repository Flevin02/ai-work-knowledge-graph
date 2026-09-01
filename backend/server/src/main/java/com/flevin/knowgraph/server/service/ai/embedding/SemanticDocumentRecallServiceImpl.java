package com.flevin.knowgraph.server.service.ai.embedding;

import com.flevin.knowgraph.server.model.ai.embedding.DocumentEmbeddingIndexResult;
import com.flevin.knowgraph.server.model.ai.embedding.EmbeddingModelDescriptor;
import com.flevin.knowgraph.server.model.ai.embedding.SemanticDocumentCandidate;
import com.flevin.knowgraph.server.model.ai.embedding.SemanticDocumentRecall;
import com.flevin.knowgraph.server.model.ai.embedding.SemanticChunkCandidate;
import com.flevin.knowgraph.server.model.ai.embedding.SemanticChunkVector;
import com.flevin.knowgraph.server.model.ai.rag.DocumentChunk;
import com.flevin.knowgraph.server.model.ai.rag.DocumentChunkIndexStateFact;
import com.flevin.knowgraph.server.model.ai.rag.DocumentSection;
import com.flevin.knowgraph.server.model.document.SourceDocument;
import com.flevin.knowgraph.server.repository.document.DocumentChunkIndexStateRepository;
import com.flevin.knowgraph.server.service.ai.rag.DocumentRagVersionResolver;
import com.flevin.knowgraph.server.service.ai.rag.DocumentStructurePersistenceService;
import com.flevin.knowgraph.server.service.ai.rag.PrdMarkdownSectionParser;
import com.flevin.knowgraph.server.service.ai.rag.SectionAwareDocumentChunker;
import com.flevin.knowgraph.server.repository.document.SourceDocumentRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 独立文档语义召回实现，使用 Fake Embedding 与精确 COSINE 扫描验证实验数据流。
 *
 * <p>召回流程：确定性解析并幂等持久化主体章节与分片 → 补建缺失向量事实 →
 * 读取同一空间、同一分片版本和模型边界的就绪向量 → 以主体分片向量为查询执行
 * 精确 COSINE → 按文档 max 池化聚合为文档级候选。该结果不代表真实模型语义质量，
 * 也不接入默认文档关联候选链路。</p>
 */
@Service
public class SemanticDocumentRecallServiceImpl implements DocumentSemanticRecallService {

    private final SourceDocumentRepository sourceDocumentRepository;
    private final PrdMarkdownSectionParser sectionParser;
    private final SectionAwareDocumentChunker documentChunker;
    private final DocumentStructurePersistenceService structurePersistenceService;
    private final DocumentEmbeddingIndexService embeddingIndexService;
    private final DocumentChunkIndexStateRepository indexStateRepository;
    private final DocumentRagVersionResolver versionResolver;
    private final ExactCosineRetriever exactCosineRetriever;

    public SemanticDocumentRecallServiceImpl(
            SourceDocumentRepository sourceDocumentRepository,
            PrdMarkdownSectionParser sectionParser,
            SectionAwareDocumentChunker documentChunker,
            DocumentStructurePersistenceService structurePersistenceService,
            DocumentEmbeddingIndexService embeddingIndexService,
            DocumentChunkIndexStateRepository indexStateRepository,
            DocumentRagVersionResolver versionResolver
    ) {
        this.sourceDocumentRepository = sourceDocumentRepository;
        this.sectionParser = sectionParser;
        this.documentChunker = documentChunker;
        this.structurePersistenceService = structurePersistenceService;
        this.embeddingIndexService = embeddingIndexService;
        this.indexStateRepository = indexStateRepository;
        this.versionResolver = versionResolver;
        this.exactCosineRetriever = new ExactCosineRetriever();
    }

    /**
     * 按冻结的 TopK=8 规则召回当前文档的语义候选文档。
     *
     * @param spaceId 知识空间标识
     * @param sourceDocumentId 当前作为召回主体的来源资料标识
     * @return 文档级语义召回结果；主体没有可索引分片或空间内没有就绪向量时返回空候选
     */
    @Override
    public SemanticDocumentRecall recall(
            Long spaceId,
            Long sourceDocumentId
    ) {
        if (spaceId == null || spaceId <= 0 || sourceDocumentId == null || sourceDocumentId <= 0) {
            throw new IllegalArgumentException("知识空间和来源资料标识必须大于零");
        }

        // 读取主体来源资料，确保召回主体确实属于当前知识空间
        SourceDocument document = sourceDocumentRepository
                .findById(spaceId, sourceDocumentId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "当前知识空间不存在该来源资料: " + sourceDocumentId));

        // 获取当前完整分片版本，作为章节、分片和向量事实的共同隔离边界
        String chunkVersion = versionResolver.chunkVersion();

        // 确定性解析主体原文；空白原文没有可追溯章节，直接返回空召回
        List<DocumentSection> sections = sectionParser.parse(document.contentText());
        List<DocumentChunk> chunks = documentChunker.chunk(sections);
        if (chunks.isEmpty()) {
            // 空态仍读取模型描述，便于报告记录供应商、模型和维度边界
            EmbeddingModelDescriptor emptyDescriptor = embeddingIndexService
                    .indexDocument(spaceId, sourceDocumentId)
                    .descriptor();
            return emptyRecall(spaceId, sourceDocumentId, chunkVersion, emptyDescriptor);
        }

        // 幂等持久化主体章节与分片事实，保证向量可以随时从 MySQL 重建
        structurePersistenceService.persist(document, sections, chunks);

        // 为主体缺失分片补建向量，并取得本次召回的模型描述边界
        DocumentEmbeddingIndexResult indexResult = embeddingIndexService
                .indexDocument(spaceId, sourceDocumentId);
        EmbeddingModelDescriptor descriptor = indexResult.descriptor();

        // 读取同一空间、同一分片版本和完整模型描述下全部就绪向量事实，
        // 并转换为精确检索使用的语义向量模型，保持模型描述与持久化事实一致
        List<SemanticChunkVector> indexedVectors = indexStateRepository.findReady(
                spaceId,
                chunkVersion,
                descriptor.provider(),
                descriptor.model(),
                descriptor.version(),
                descriptor.dimension()
        ).stream()
                .map(this::toSemanticVector)
                .toList();

        // 主体分片向量作为查询；没有就绪查询向量说明索引尚未建立，返回空召回
        List<SemanticChunkVector> queryVectors = indexedVectors.stream()
                .filter(vector -> vector.sourceDocumentId().equals(sourceDocumentId))
                .toList();
        if (queryVectors.isEmpty()) {
            return emptyRecall(spaceId, sourceDocumentId, chunkVersion, descriptor);
        }

        // 逐查询分片执行精确 COSINE，并按文档 max 池化聚合最高分片相似度
        Map<Long, SemanticDocumentCandidate> bestByDocument = new LinkedHashMap<>();
        for (SemanticChunkVector queryVector : queryVectors) {
            // 检索器在分片层排除主体资料，文档级候选天然不包含自关联
            List<SemanticChunkCandidate> chunkCandidates = exactCosineRetriever.retrieve(
                    spaceId,
                    sourceDocumentId,
                    chunkVersion,
                    descriptor,
                    queryVector.vector(),
                    indexedVectors,
                    CHUNK_QUERY_TOP_K
            );
            for (SemanticChunkCandidate chunkCandidate : chunkCandidates) {
                mergeChunkCandidate(bestByDocument, chunkCandidate);
            }
        }

        // 按最高分片相似度降序、来源标识升序稳定排序，截取文档级 TopK 并赋秩
        List<SemanticDocumentCandidate> candidates = bestByDocument.values().stream()
                .sorted(Comparator
                        .comparingDouble(SemanticDocumentCandidate::bestChunkScore)
                        .reversed()
                        .thenComparing(SemanticDocumentCandidate::sourceDocumentId))
                .limit(DOCUMENT_TOP_K)
                .toList();
        List<SemanticDocumentCandidate> rankedCandidates = new ArrayList<>(candidates.size());
        for (int index = 0; index < candidates.size(); index++) {
            SemanticDocumentCandidate candidate = candidates.get(index);
            rankedCandidates.add(new SemanticDocumentCandidate(
                    candidate.sourceDocumentId(),
                    candidate.bestChunkScore(),
                    candidate.bestChunkId(),
                    candidate.bestChunkRecordId(),
                    index + 1
            ));
        }

        return new SemanticDocumentRecall(
                spaceId,
                sourceDocumentId,
                SEMANTIC_RECALL_POLICY_VERSION,
                chunkVersion,
                descriptor,
                DOCUMENT_TOP_K,
                rankedCandidates,
                queryVectors.size()
        );
    }

    /**
     * 将持久化向量索引事实转换为精确检索使用的语义向量模型。
     *
     * @param fact 已就绪向量索引事实
     * @return 语义向量事实
     */
    private SemanticChunkVector toSemanticVector(DocumentChunkIndexStateFact fact) {
        return new SemanticChunkVector(
                fact.spaceId(),
                fact.sourceDocumentId(),
                fact.chunkRecordId(),
                fact.chunkId(),
                fact.contentHash(),
                fact.chunkVersion(),
                new EmbeddingModelDescriptor(
                        fact.embeddingProvider(),
                        fact.embeddingModel(),
                        fact.embeddingVersion(),
                        fact.dimension()
                ),
                fact.vector()
        );
    }

    /**
     * 将一条分片级候选合并进文档级 max 池化映射。
     *
     * @param bestByDocument 文档标识到当前最优候选的映射
     * @param chunkCandidate 分片级候选
     */
    private void mergeChunkCandidate(
            Map<Long, SemanticDocumentCandidate> bestByDocument,
            SemanticChunkCandidate chunkCandidate
    ) {
        SemanticDocumentCandidate existing = bestByDocument.get(chunkCandidate.sourceDocumentId());
        // 相似度相同保留先出现的分片，保证结果对稳定排序的确定性依赖
        if (existing == null || chunkCandidate.score() > existing.bestChunkScore()) {
            bestByDocument.put(chunkCandidate.sourceDocumentId(), new SemanticDocumentCandidate(
                    chunkCandidate.sourceDocumentId(),
                    chunkCandidate.score(),
                    chunkCandidate.chunkId(),
                    chunkCandidate.chunkRecordId(),
                    0
            ));
        }
    }

    /**
     * 创建没有可用分片或就绪向量的空召回结果。
     *
     * @param spaceId 知识空间标识
     * @param sourceDocumentId 来源资料标识
     * @param chunkVersion 分片策略版本
     * @return 空候选的语义召回结果
     */
    private SemanticDocumentRecall emptyRecall(
            Long spaceId,
            Long sourceDocumentId,
            String chunkVersion,
            EmbeddingModelDescriptor descriptor
    ) {
        return new SemanticDocumentRecall(
                spaceId,
                sourceDocumentId,
                SEMANTIC_RECALL_POLICY_VERSION,
                chunkVersion,
                descriptor,
                DOCUMENT_TOP_K,
                List.of(),
                0
        );
    }
}

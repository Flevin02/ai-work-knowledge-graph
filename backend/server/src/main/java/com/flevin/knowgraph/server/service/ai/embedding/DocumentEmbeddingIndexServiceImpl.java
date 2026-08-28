package com.flevin.knowgraph.server.service.ai.embedding;

import com.flevin.knowgraph.server.model.ai.embedding.DocumentEmbeddingIndexResult;
import com.flevin.knowgraph.server.model.ai.embedding.EmbeddingModelDescriptor;
import com.flevin.knowgraph.server.model.ai.embedding.EmbeddingVector;
import com.flevin.knowgraph.server.model.ai.rag.DocumentChunkFact;
import com.flevin.knowgraph.server.model.ai.rag.DocumentChunkIndexStateFact;
import com.flevin.knowgraph.server.repository.document.DocumentChunkIndexStateRepository;
import com.flevin.knowgraph.server.repository.document.DocumentChunkRepository;
import com.flevin.knowgraph.server.service.ai.rag.DocumentRagVersionResolver;
import com.flevin.knowgraph.server.util.SnowflakeIdGenerator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Fake Embedding 索引服务实现，只验证向量生成、复用、版本隔离和原子持久化数据流。
 *
 * <p>该服务当前没有 Controller，也不接入默认文档关联候选；Fake 排序结果不能代表真实语义质量。</p>
 */
@Service
public class DocumentEmbeddingIndexServiceImpl implements DocumentEmbeddingIndexService {

    private final DocumentChunkRepository chunkRepository;
    private final DocumentChunkIndexStateRepository indexStateRepository;
    private final DocumentRagVersionResolver versionResolver;
    private final DocumentEmbeddingClient embeddingClient;

    public DocumentEmbeddingIndexServiceImpl(
            DocumentChunkRepository chunkRepository,
            DocumentChunkIndexStateRepository indexStateRepository,
            DocumentRagVersionResolver versionResolver,
            @Qualifier("fakeDocumentEmbeddingClient") DocumentEmbeddingClient embeddingClient
    ) {
        this.chunkRepository = chunkRepository;
        this.indexStateRepository = indexStateRepository;
        this.versionResolver = versionResolver;
        this.embeddingClient = embeddingClient;
    }

    /**
     * 为一份来源资料的当前分片版本生成缺失向量事实。
     *
     * @param spaceId 知识空间标识
     * @param sourceDocumentId 来源资料标识
     * @return 当前版本分片总数、复用数量和新增索引数量
     */
    @Override
    public DocumentEmbeddingIndexResult indexDocument(
            Long spaceId,
            Long sourceDocumentId
    ) {
        if (spaceId == null || spaceId <= 0 || sourceDocumentId == null || sourceDocumentId <= 0) {
            throw new IllegalArgumentException("知识空间和来源资料标识必须大于零");
        }

        // 获取当前完整分片版本，确保旧策略分片不会参与本次索引任务
        String chunkVersion = versionResolver.chunkVersion();
        // 查询当前资料的可重建分片事实，按来源原文顺序返回
        List<DocumentChunkFact> chunks = chunkRepository.findByDocument(
                spaceId,
                sourceDocumentId,
                chunkVersion
        );

        // 读取 Fake 模型完整描述，后续复用和写入均以该描述为兼容边界
        EmbeddingModelDescriptor descriptor = Objects.requireNonNull(
                embeddingClient.descriptor(),
                "Embedding 模型描述不能为空"
        );
        if (chunks.isEmpty()) {
            return new DocumentEmbeddingIndexResult(descriptor, chunkVersion, 0, 0, 0);
        }

        // 查询当前资料已经就绪且与模型、维度和分片版本完全兼容的向量事实
        Map<IndexKey, DocumentChunkIndexStateFact> existingByKey = indexStateRepository
                .findReadyByDocument(
                        spaceId,
                        sourceDocumentId,
                        chunkVersion,
                        descriptor.provider(),
                        descriptor.model(),
                        descriptor.version(),
                        descriptor.dimension()
                ).stream()
                .collect(Collectors.toMap(
                        fact -> new IndexKey(fact.chunkRecordId(), fact.contentHash()),
                        Function.identity(),
                        (left, right) -> {
                            throw new IllegalStateException("分片向量唯一键出现重复就绪记录");
                        },
                        LinkedHashMap::new
                ));

        // 只选择缺少兼容就绪向量的分片，内容指纹变化时必须重新生成
        List<DocumentChunkFact> missingChunks = chunks.stream()
                .filter(chunk -> !existingByKey.containsKey(
                        new IndexKey(chunk.id(), chunk.contentHash())
                ))
                .toList();
        if (missingChunks.isEmpty()) {
            return new DocumentEmbeddingIndexResult(
                    descriptor,
                    chunkVersion,
                    chunks.size(),
                    chunks.size(),
                    0
            );
        }

        // 批量调用 Fake Embedding，保持输入和输出顺序一一对应
        List<EmbeddingVector> vectors = embeddingClient.embed(
                missingChunks.stream().map(DocumentChunkFact::contentText).toList()
        );

        // 在任何数据库写入前校验完整返回数量、非空向量和统一维度
        validateVectors(missingChunks, vectors, descriptor);

        Instant createdAt = Instant.now();
        // 将完整已校验向量转换为可重建索引事实，尚不写入任何关系或审核状态
        List<DocumentChunkIndexStateFact> newFacts = java.util.stream.IntStream
                .range(0, missingChunks.size())
                .mapToObj(index -> createReadyFact(
                        missingChunks.get(index),
                        vectors.get(index),
                        descriptor,
                        chunkVersion,
                        createdAt
                ))
                .toList();

        // 在独立短事务中写入完整批次，数据库异常时不会留下半批向量
        indexStateRepository.saveAll(newFacts);

        return new DocumentEmbeddingIndexResult(
                descriptor,
                chunkVersion,
                chunks.size(),
                chunks.size() - missingChunks.size(),
                missingChunks.size()
        );
    }

    /**
     * 校验 Embedding 客户端返回的整批向量。
     *
     * @param chunks 待生成向量的分片
     * @param vectors 客户端返回向量
     * @param descriptor 模型描述
     */
    private void validateVectors(
            List<DocumentChunkFact> chunks,
            List<EmbeddingVector> vectors,
            EmbeddingModelDescriptor descriptor
    ) {
        if (vectors == null || vectors.size() != chunks.size()) {
            throw new IllegalStateException("Embedding 返回数量与分片数量不一致");
        }
        for (int index = 0; index < vectors.size(); index++) {
            EmbeddingVector vector = vectors.get(index);
            if (vector == null) {
                throw new IllegalStateException("Embedding 返回了空向量，分片序号: " + (index + 1));
            }
            if (vector.dimension() != descriptor.dimension()) {
                throw new IllegalStateException("Embedding 向量维度与模型描述不一致，分片序号: " + (index + 1));
            }
        }
    }

    /**
     * 创建一条已经完整校验的就绪向量事实。
     *
     * @param chunk 分片事实
     * @param vector Embedding 向量
     * @param descriptor 模型描述
     * @param chunkVersion 分片版本
     * @param createdAt 本批统一创建时间
     * @return 可原子写入的就绪索引事实
     */
    private DocumentChunkIndexStateFact createReadyFact(
            DocumentChunkFact chunk,
            EmbeddingVector vector,
            EmbeddingModelDescriptor descriptor,
            String chunkVersion,
            Instant createdAt
    ) {
        return new DocumentChunkIndexStateFact(
                SnowflakeIdGenerator.nextId(),
                chunk.spaceId(),
                chunk.sourceDocumentId(),
                chunk.id(),
                chunk.chunkId(),
                chunk.contentHash(),
                chunkVersion,
                descriptor.provider(),
                descriptor.model(),
                descriptor.version(),
                descriptor.dimension(),
                vector,
                "ready",
                null,
                createdAt,
                createdAt
        );
    }

    /**
     * 已就绪向量复用键。
     *
     * @param chunkRecordId 分片事实标识
     * @param contentHash 分片内容指纹
     */
    private record IndexKey(Long chunkRecordId, String contentHash) {
    }
}

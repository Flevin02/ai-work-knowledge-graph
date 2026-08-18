package com.flevin.knowgraph.server.service.ai.impl;

import com.flevin.knowgraph.common.enums.ErrorCode;
import com.flevin.knowgraph.common.exception.TipsException;
import com.flevin.knowgraph.server.config.properties.AiProperties;
import com.flevin.knowgraph.server.model.ai.AiChunkExtractionResult;
import com.flevin.knowgraph.server.model.ai.AiDocumentExtractionResponse;
import com.flevin.knowgraph.server.model.ai.AiExtractionRequest;
import com.flevin.knowgraph.server.model.ai.AiExtractionResult;
import com.flevin.knowgraph.server.model.ai.AiExtractionRunDetail;
import com.flevin.knowgraph.server.model.ai.AiExtractionRunSummary;
import com.flevin.knowgraph.server.model.ai.rag.DocumentChunk;
import com.flevin.knowgraph.server.model.ai.rag.DocumentSection;
import com.flevin.knowgraph.server.model.document.SourceDocument;
import com.flevin.knowgraph.server.repository.ai.AiExtractionRunRepository;
import com.flevin.knowgraph.server.repository.document.SourceDocumentRepository;
import com.flevin.knowgraph.server.repository.entity.AiExtractionRunEntity;
import com.flevin.knowgraph.server.service.ai.AiExtractionClient;
import com.flevin.knowgraph.server.service.ai.AiExtractionService;
import com.flevin.knowgraph.server.service.ai.AiExtractionValidationException;
import com.flevin.knowgraph.server.service.ai.rag.PrdMarkdownSectionParser;
import com.flevin.knowgraph.server.service.ai.rag.SectionAwareDocumentChunker;
import com.flevin.knowgraph.server.service.space.KnowledgeSpaceService;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.time.Instant;
import java.util.UUID;

/**
 * 来源资料 AI 抽取编排实现，第一阶段只返回候选预览，不写入图谱事实。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiExtractionServiceImpl implements AiExtractionService {

    private final SourceDocumentRepository sourceDocumentRepository;
    private final KnowledgeSpaceService knowledgeSpaceService;
    private final PrdMarkdownSectionParser sectionParser;
    private final SectionAwareDocumentChunker documentChunker;
    private final ObjectProvider<AiExtractionClient> extractionClientProvider;
    private final AiProperties aiProperties;
    private final AiExtractionRunRepository extractionRunRepository;
    private final ObjectMapper objectMapper;

    /**
     * 对已导入来源资料执行章节解析、分片和结构化抽取预览。
     *
     * @param spaceId 知识空间标识
     * @param documentId 来源资料标识
     * @return 按来源分片组织的结构化候选结果
     */
    @Override
    public AiDocumentExtractionResponse extractDocument(
            String spaceId,
            String documentId
    ) {
        // 校验来源资料所属知识空间当前有效
        knowledgeSpaceService.requireActive(spaceId);

        // 查询指定知识空间内的完整来源资料，防止跨空间抽取
        SourceDocument document = sourceDocumentRepository.findById(spaceId, documentId)
                .orElseThrow(() -> new TipsException(ErrorCode.NOT_FOUND, "来源资料不存在"));

        String extractionId = UUID.randomUUID().toString();
        Instant createdAt = Instant.now();
        AiExtractionRunEntity extractionRun = createProcessingRun(
                extractionId,
                spaceId,
                document,
                createdAt
        );
        extractionRunRepository.save(extractionRun);

        // 获取当前已启用并完成配置的真实模型客户端
        AiExtractionClient extractionClient = extractionClientProvider.getIfAvailable();
        if (extractionClient == null) {
            // 记录未启用 AI 的失败运行，方便页面查看历史状态
            extractionRunRepository.fail(
                    extractionId,
                    "AI 服务未启用，请检查 AI_ENABLED 和 AI_API_KEY",
                    Instant.now().toString()
            );
            throw new TipsException(
                    ErrorCode.AI_SERVICE_UNAVAILABLE,
                    "AI 服务未启用，请检查 AI_ENABLED 和 AI_API_KEY"
            );
        }

        try {
            // 使用确定性 Markdown 规则解析章节路径和原文偏移
            List<DocumentSection> sections = sectionParser.parse(document.contentText());

            // 按章节边界生成可追溯文本分片
            List<DocumentChunk> chunks = documentChunker.chunk(sections);

            // 逐分片串行调用模型，避免一次预览产生不可控并发和费用
            List<AiChunkExtractionResult> chunkResults = chunks.stream()
                    .map(chunk -> extractChunk(document, chunk, extractionClient))
                    .toList();

            Instant completedAt = Instant.now();
            AiDocumentExtractionResponse response = new AiDocumentExtractionResponse(
                    extractionId,
                    "completed",
                    createdAt,
                    completedAt,
                    document.id(),
                    document.name(),
                    document.documentType(),
                    aiProperties.getProvider(),
                    aiProperties.getModel(),
                    aiProperties.getPromptVersion(),
                    aiProperties.getSchemaVersion(),
                    sections.size(),
                    chunks.size(),
                    chunkResults
            );

            // 序列化完整结果并保存，保证刷新页面后仍可重新查看
            extractionRunRepository.complete(
                    extractionId,
                    sections.size(),
                    chunks.size(),
                    writeResultJson(response),
                    completedAt.toString()
            );
            return response;
        } catch (RuntimeException exception) {
            // 保存失败状态后继续抛出原有业务错误，前端可通过历史记录查看原因
            extractionRunRepository.fail(
                    extractionId,
                    resolveErrorMessage(exception),
                    Instant.now().toString()
            );
            throw exception;
        }
    }

    /**
     * 查询来源资料的历史抽取摘要。
     *
     * @param spaceId 知识空间标识
     * @param documentId 来源资料标识
     * @return 抽取运行摘要
     */
    @Override
    public List<AiExtractionRunSummary> listExtractions(String spaceId, String documentId) {
        // 校验来源资料所属知识空间当前有效
        knowledgeSpaceService.requireActive(spaceId);

        // 确认来源资料存在且当前可访问
        requireDocument(spaceId, documentId);

        // 查询历史运行并转换为不带完整 JSON 的摘要
        return extractionRunRepository.findAllByDocument(spaceId, documentId).stream()
                .map(this::toSummary)
                .toList();
    }

    /**
     * 查询单次抽取的摘要和完整结果。
     *
     * @param spaceId 知识空间标识
     * @param documentId 来源资料标识
     * @param extractionId 抽取运行标识
     * @return 抽取运行详情
     */
    @Override
    public AiExtractionRunDetail getExtraction(
            String spaceId,
            String documentId,
            String extractionId
    ) {
        // 校验来源资料所属知识空间当前有效
        knowledgeSpaceService.requireActive(spaceId);

        // 查询指定来源资料的一次历史抽取运行
        AiExtractionRunEntity entity = extractionRunRepository.findById(
                        spaceId,
                        documentId,
                        extractionId
                )
                .orElseThrow(() -> new TipsException(ErrorCode.NOT_FOUND, "AI 抽取记录不存在"));

        // 失败运行没有完整结果 JSON，只返回状态和错误摘要
        AiDocumentExtractionResponse result = entity.getResultJson() == null
                ? null
                : readResultJson(entity.getResultJson());
        return new AiExtractionRunDetail(toSummary(entity), result);
    }

    private AiExtractionRunEntity createProcessingRun(
            String extractionId,
            String spaceId,
            SourceDocument document,
            Instant createdAt
    ) {
        AiExtractionRunEntity entity = new AiExtractionRunEntity();
        entity.setId(extractionId);
        entity.setSpaceId(spaceId);
        entity.setSourceDocumentId(document.id());
        entity.setProvider(aiProperties.getProvider());
        entity.setModel(aiProperties.getModel());
        entity.setPromptVersion(aiProperties.getPromptVersion());
        entity.setSchemaVersion(aiProperties.getSchemaVersion());
        entity.setStatus("processing");
        entity.setSectionCount(0);
        entity.setChunkCount(0);
        entity.setCreatedAt(createdAt.toString());
        return entity;
    }

    private SourceDocument requireDocument(String spaceId, String documentId) {
        return sourceDocumentRepository.findById(spaceId, documentId)
                .orElseThrow(() -> new TipsException(ErrorCode.NOT_FOUND, "来源资料不存在"));
    }

    private AiExtractionRunSummary toSummary(AiExtractionRunEntity entity) {
        return new AiExtractionRunSummary(
                entity.getId(),
                entity.getStatus(),
                entity.getProvider(),
                entity.getModel(),
                entity.getPromptVersion(),
                entity.getSchemaVersion(),
                entity.getSectionCount(),
                entity.getChunkCount(),
                entity.getErrorMessage(),
                Instant.parse(entity.getCreatedAt()),
                entity.getCompletedAt() == null ? null : Instant.parse(entity.getCompletedAt())
        );
    }

    private String writeResultJson(AiDocumentExtractionResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法保存 AI 抽取结果", exception);
        }
    }

    private AiDocumentExtractionResponse readResultJson(String resultJson) {
        try {
            return objectMapper.readValue(resultJson, AiDocumentExtractionResponse.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("AI 抽取历史结果不是有效 JSON", exception);
        }
    }

    private String resolveErrorMessage(RuntimeException exception) {
        if (exception instanceof TipsException) {
            return exception.getMessage();
        }
        return "AI 抽取失败，请查看服务端日志和模型配置";
    }

    /**
     * 对单个来源分片执行真实模型调用和服务端结果校验。
     *
     * @param document 来源资料
     * @param chunk 来源分片
     * @param extractionClient 已启用模型客户端
     * @return 当前分片结构化候选结果
     */
    private AiChunkExtractionResult extractChunk(
            SourceDocument document,
            DocumentChunk chunk,
            AiExtractionClient extractionClient
    ) {
        AiExtractionRequest request = new AiExtractionRequest(
                document.id(),
                document.name(),
                document.documentType().getValue(),
                chunk.chunkId(),
                chunk.sectionPath(),
                chunk.contentText()
        );

        try {
            // 调用领域抽取客户端并完成结构、引用和证据校验
            AiExtractionResult extraction = extractionClient.extract(request);
            return new AiChunkExtractionResult(
                    chunk.chunkId(),
                    chunk.sectionPath(),
                    extraction
            );
        } catch (AiExtractionValidationException exception) {
            log.warn(
                    "AI 抽取结果校验失败: documentId={}, chunkId={}",
                    document.id(),
                    chunk.chunkId(),
                    exception
            );
            throw new TipsException(
                    ErrorCode.AI_SERVICE_UNAVAILABLE,
                    "AI 返回的结构化结果未通过证据校验"
            );
        } catch (RuntimeException exception) {
            log.error(
                    "AI 抽取调用失败: documentId={}, chunkId={}",
                    document.id(),
                    chunk.chunkId(),
                    exception
            );
            throw new TipsException(
                    ErrorCode.AI_SERVICE_UNAVAILABLE,
                    "AI 服务调用失败，请稍后重试"
            );
        }
    }
}

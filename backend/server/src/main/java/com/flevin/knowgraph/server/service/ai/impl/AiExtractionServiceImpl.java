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
import com.flevin.knowgraph.server.model.ai.AiExtractionStreamEvents;
import com.flevin.knowgraph.server.model.ai.rag.DocumentChunk;
import com.flevin.knowgraph.server.model.ai.rag.DocumentSection;
import com.flevin.knowgraph.server.model.document.SourceDocument;
import com.flevin.knowgraph.server.repository.ai.AiExtractionRunRepository;
import com.flevin.knowgraph.server.repository.document.SourceDocumentRepository;
import com.flevin.knowgraph.server.repository.entity.AiExtractionRunEntity;
import com.flevin.knowgraph.server.service.ai.AiExtractionClient;
import com.flevin.knowgraph.server.service.ai.AiExtractionEventPublisher;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 来源资料 AI 抽取编排实现，第一阶段只返回候选预览，不写入图谱事实。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiExtractionServiceImpl implements AiExtractionService {

    private static final int DOCUMENT_SUMMARY_MAX_LENGTH = 160;

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
        // 使用空事件发布器复用同一编排逻辑，保持同步服务调用兼容
        return executeExtraction(spaceId, documentId, (eventName, payload) -> {
        });
    }

    /**
     * 对已导入来源资料执行流式结构化抽取预览，并把运行事件交给传输层。
     *
     * @param spaceId 知识空间标识
     * @param documentId 来源资料标识
     * @param eventPublisher 抽取运行事件发布器
     */
    @Override
    public void streamDocument(
            String spaceId,
            String documentId,
            AiExtractionEventPublisher eventPublisher
    ) {
        try {
            // 执行完整抽取编排；失败事件由核心流程在持久化失败状态后发布
            executeExtraction(spaceId, documentId, eventPublisher);
        } catch (RuntimeException exception) {
            log.debug(
                    "AI 流式抽取已通过 error 事件结束: spaceId={}, documentId={}",
                    spaceId,
                    documentId,
                    exception
            );
        }
    }

    /**
     * 执行可同时服务同步响应和流式事件的抽取核心流程。
     *
     * @param spaceId 知识空间标识
     * @param documentId 来源资料标识
     * @param eventPublisher 抽取运行事件发布器
     * @return 完整结构化抽取结果
     */
    private AiDocumentExtractionResponse executeExtraction(
            String spaceId,
            String documentId,
            AiExtractionEventPublisher eventPublisher
    ) {
        String extractionId = UUID.randomUUID().toString();
        SourceDocument document = null;
        DocumentChunk currentChunk = null;
        boolean runSaved = false;

        try {
            // 校验来源资料所属知识空间当前有效
            knowledgeSpaceService.requireActive(spaceId);

            // 查询指定知识空间内的完整来源资料，防止跨空间抽取
            document = sourceDocumentRepository.findById(spaceId, documentId)
                    .orElseThrow(() -> new TipsException(ErrorCode.NOT_FOUND, "来源资料不存在"));

            Instant createdAt = Instant.now();

            // 创建包含模型和版本快照的 processing 运行记录
            AiExtractionRunEntity extractionRun = createProcessingRun(
                    extractionId,
                    spaceId,
                    document,
                    createdAt
            );

            // 先持久化运行记录，使后续每个事件都能通过 extractionRunId 恢复状态
            extractionRunRepository.save(extractionRun);
            runSaved = true;

            // 立即发布真实运行创建事件，避免模型首个 Token 前完全静默
            eventPublisher.publish(
                    AiExtractionStreamEvents.RUN_STARTED,
                    new AiExtractionStreamEvents.RunStarted(
                            extractionId,
                            document.id(),
                            document.name(),
                            aiProperties.getProvider(),
                            aiProperties.getModel(),
                            aiProperties.getPromptVersion(),
                            aiProperties.getSchemaVersion(),
                            createdAt,
                            true
                    )
            );

            // 获取当前已启用并完成配置的真实模型客户端
            AiExtractionClient extractionClient = extractionClientProvider.getIfAvailable();
            if (extractionClient == null) {
                throw new TipsException(
                        ErrorCode.AI_SERVICE_UNAVAILABLE,
                        "AI 服务未启用，请检查 AI_ENABLED 和 AI_API_KEY"
                );
            }

            // 使用确定性 Markdown 规则解析章节路径和原文偏移
            List<DocumentSection> sections = sectionParser.parse(document.contentText());

            // 按章节边界生成可追溯文本分片
            List<DocumentChunk> chunks = documentChunker.chunk(sections);

            // 保存确定性章节和分片总数，处理中或失败后仍可恢复计划边界
            extractionRunRepository.plan(extractionId, sections.size(), chunks.size());

            List<AiChunkExtractionResult> chunkResults = new ArrayList<>(chunks.size());
            for (int index = 0; index < chunks.size(); index++) {
                currentChunk = chunks.get(index);
                int chunkIndex = index + 1;

                // 在真实模型调用前发布分片定位和总进度
                eventPublisher.publish(
                        AiExtractionStreamEvents.CHUNK_STARTED,
                        new AiExtractionStreamEvents.ChunkStarted(
                                extractionId,
                                document.id(),
                                currentChunk.chunkId(),
                                currentChunk.sectionPath(),
                                chunkIndex,
                                chunks.size(),
                                Instant.now()
                        )
                );

                // 串行调用当前分片模型，并只转发供应商真实返回的文本增量
                AiChunkExtractionResult chunkResult = extractChunk(
                        extractionId,
                        document,
                        currentChunk,
                        extractionClient,
                        eventPublisher
                );
                chunkResults.add(chunkResult);

                // 完整分片通过结构和证据校验后再发布候选结果
                eventPublisher.publish(
                        AiExtractionStreamEvents.CHUNK_COMPLETED,
                        new AiExtractionStreamEvents.ChunkCompleted(
                                extractionId,
                                document.id(),
                                currentChunk.chunkId(),
                                currentChunk.sectionPath(),
                                chunkIndex,
                                chunks.size(),
                                chunkResult,
                                Instant.now()
                        )
                );
            }

            // 按原文分片顺序聚合模型摘要，形成来源资料卡片使用的文档级摘要
            String documentSummary = buildDocumentSummary(chunkResults);

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
                    documentSummary,
                    List.copyOf(chunkResults)
            );

            // 序列化完整结果并保存，保证刷新页面后仍可重新查看
            extractionRunRepository.complete(
                    extractionId,
                    sections.size(),
                    chunks.size(),
                    documentSummary,
                    writeResultJson(response),
                    completedAt.toString()
            );

            // 只有完整结果落库成功后才发布最终完成事件
            eventPublisher.publish(
                    AiExtractionStreamEvents.COMPLETED,
                    new AiExtractionStreamEvents.Completed(
                            extractionId,
                            document.id(),
                            response,
                            completedAt
                    )
            );
            return response;
        } catch (RuntimeException exception) {
            String errorMessage = resolveErrorMessage(exception);
            if (runSaved) {
                // 已持久化运行在失败时保存稳定错误摘要，部分模型文本不写入 result_json
                extractionRunRepository.fail(
                        extractionId,
                        errorMessage,
                        Instant.now().toString()
                );
            }

            // 以稳定 error 事件结束流；发布失败不能覆盖原始抽取异常
            publishFailureEvent(
                    eventPublisher,
                    extractionId,
                    document == null ? documentId : document.id(),
                    currentChunk == null ? null : currentChunk.chunkId(),
                    errorMessage,
                    runSaved
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

    /**
     * 按来源分片顺序聚合模型摘要，并限制为资料列表可展示的长度。
     *
     * @param chunkResults 已通过结构和证据校验的分片抽取结果
     * @return 去除重复内容后不超过 160 个字符的文档摘要
     */
    private String buildDocumentSummary(List<AiChunkExtractionResult> chunkResults) {
        // 规范化每个分片摘要并去除完全重复内容，保留原始分片顺序
        List<String> summaries = chunkResults.stream()
                .map(chunk -> chunk.extraction().summary().replaceAll("\\s+", " ").strip())
                .distinct()
                .toList();

        // 使用中文分号连接分片摘要，避免再次产生模型调用和额外费用
        String documentSummary = String.join("；", summaries);

        // 未超过列表摘要边界时保留完整聚合结果
        if (documentSummary.length() <= DOCUMENT_SUMMARY_MAX_LENGTH) {
            return documentSummary;
        }

        // 截取超长聚合结果，保持与来源资料卡片现有 160 字边界一致
        return documentSummary.substring(0, DOCUMENT_SUMMARY_MAX_LENGTH);
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
     * 发布稳定失败事件，并避免传输层异常覆盖原始抽取错误。
     *
     * @param eventPublisher 抽取运行事件发布器
     * @param extractionId 抽取记录标识
     * @param documentId 来源资料标识
     * @param chunkId 失败发生时正在处理的分片标识
     * @param errorMessage 面向用户的稳定错误摘要
     * @param recoverable 当前运行是否已经持久化
     */
    private void publishFailureEvent(
            AiExtractionEventPublisher eventPublisher,
            String extractionId,
            String documentId,
            String chunkId,
            String errorMessage,
            boolean recoverable
    ) {
        try {
            // 发布可由前端状态机识别的终止事件，不包含异常堆栈或敏感模型正文
            eventPublisher.publish(
                    AiExtractionStreamEvents.ERROR,
                    new AiExtractionStreamEvents.Error(
                            extractionId,
                            documentId,
                            chunkId,
                            errorMessage,
                            recoverable,
                            Instant.now()
                    )
            );
        } catch (RuntimeException publishException) {
            log.warn(
                    "AI 抽取失败事件发送异常: extractionId={}, documentId={}",
                    extractionId,
                    documentId,
                    publishException
            );
        }
    }

    /**
     * 对单个来源分片执行真实模型调用和服务端结果校验。
     *
     * @param extractionId 抽取记录标识
     * @param document 来源资料
     * @param chunk 来源分片
     * @param extractionClient 已启用模型客户端
     * @param eventPublisher 抽取运行事件发布器
     * @return 当前分片结构化候选结果
     */
    private AiChunkExtractionResult extractChunk(
            String extractionId,
            SourceDocument document,
            DocumentChunk chunk,
            AiExtractionClient extractionClient,
            AiExtractionEventPublisher eventPublisher
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
            // 调用领域抽取客户端，把供应商实际文本增量发布到当前分片事件流
            AiExtractionResult extraction = extractionClient.extract(
                    request,
                    delta -> eventPublisher.publish(
                            AiExtractionStreamEvents.DELTA,
                            new AiExtractionStreamEvents.Delta(
                                    extractionId,
                                    document.id(),
                                    chunk.chunkId(),
                                    chunk.sectionPath(),
                                    delta,
                                    Instant.now()
                            )
                    )
            );
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

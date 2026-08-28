package com.flevin.knowgraph.server.service.ai.impl;

import com.flevin.knowgraph.common.enums.ErrorCode;
import com.flevin.knowgraph.common.exception.TipsException;
import com.flevin.knowgraph.server.config.properties.AiProperties;
import com.flevin.knowgraph.server.model.ai.AiChunkExtractionResult;
import com.flevin.knowgraph.server.model.ai.AiDocumentExtractionResponse;
import com.flevin.knowgraph.server.model.ai.AiExtractionBatchResponse;
import com.flevin.knowgraph.server.model.ai.AiExtractionRequest;
import com.flevin.knowgraph.server.model.ai.AiExtractionResult;
import com.flevin.knowgraph.server.model.ai.AiExtractionRunDetail;
import com.flevin.knowgraph.server.model.ai.AiExtractionRunSummary;
import com.flevin.knowgraph.server.model.ai.AiExtractionStreamEvents;
import com.flevin.knowgraph.server.model.ai.AiDocumentSummaryRequest;
import com.flevin.knowgraph.server.model.ai.AiRelationReviewRequest;
import com.flevin.knowgraph.server.model.ai.AiRelationReviewResponse;
import com.flevin.knowgraph.server.model.ai.AiRelationReviewState;
import com.flevin.knowgraph.server.model.ai.rag.DocumentChunk;
import com.flevin.knowgraph.server.model.ai.rag.DocumentSection;
import com.flevin.knowgraph.server.model.document.SourceDocument;
import com.flevin.knowgraph.server.repository.ai.AiExtractionRunRepository;
import com.flevin.knowgraph.server.repository.document.SourceDocumentRepository;
import com.flevin.knowgraph.server.repository.entity.AiExtractionRunEntity;
import com.flevin.knowgraph.server.repository.mapping.AiExtractionRunEntityMapper;
import com.flevin.knowgraph.server.service.ai.AiExtractionClient;
import com.flevin.knowgraph.server.service.ai.AiExtractionEventPublisher;
import com.flevin.knowgraph.server.service.ai.AiExtractionGraphMaterializer;
import com.flevin.knowgraph.server.service.ai.AiExtractionService;
import com.flevin.knowgraph.server.service.ai.AiExtractionValidationException;
import com.flevin.knowgraph.server.service.ai.rag.PrdMarkdownSectionParser;
import com.flevin.knowgraph.server.service.ai.rag.SectionAwareDocumentChunker;
import com.flevin.knowgraph.server.service.ai.rag.DocumentStructurePersistenceService;
import com.flevin.knowgraph.server.service.space.KnowledgeSpaceService;
import com.flevin.knowgraph.server.util.SnowflakeIdGenerator;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.exception.RetriableException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 来源资料 AI 抽取编排实现，负责保存候选结果并将其物化为待审核图谱事实。
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
    private final DocumentStructurePersistenceService structurePersistenceService;
    private final ObjectProvider<AiExtractionClient> extractionClientProvider;
    private final AiProperties aiProperties;
    private final AiExtractionRunRepository extractionRunRepository;
    private final AiExtractionRunEntityMapper extractionRunEntityMapper;
    private final AiExtractionGraphMaterializer graphMaterializer;
    private final ObjectMapper objectMapper;
    @Qualifier("aiBatchExtractionExecutor")
    private final TaskExecutor aiBatchExtractionExecutor;

    /**
     * 对已导入来源资料执行章节解析、分片和结构化抽取预览。
     *
     * @param spaceId 知识空间标识
     * @param documentId 来源资料标识
     * @return 按来源分片组织的结构化候选结果
     */
    @Override
    public AiDocumentExtractionResponse extractDocument(
            Long spaceId,
            Long documentId
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
            Long spaceId,
            Long documentId,
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
     * 将多份来源资料提交到受控后台线程池，并由每个任务独立执行可追溯的抽取编排。
     *
     * @param spaceId 知识空间标识
     * @param documentIds 当前知识空间内待提取的来源资料标识
     * @return 已受理和因队列繁忙未受理的资料标识
     */
    @Override
    public AiExtractionBatchResponse submitBatchExtraction(
            Long spaceId,
            List<Long> documentIds
    ) {
        // 校验当前知识空间有效，避免把后台任务提交到已删除空间
        knowledgeSpaceService.requireActive(spaceId);

        List<Long> normalizedDocumentIds = documentIds.stream().toList();
        if (new LinkedHashSet<>(normalizedDocumentIds).size() != normalizedDocumentIds.size()) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "批量操作中不能重复选择同一份来源资料");
        }

        // 提交任务前确认每份资料均属于当前空间，避免后台线程延迟发现无效资料
        List<SourceDocument> documents = normalizedDocumentIds.stream()
                .map(documentId -> requireDocument(spaceId, documentId))
                .toList();
        List<Long> acceptedDocumentIds = new ArrayList<>(documents.size());
        List<Long> rejectedDocumentIds = new ArrayList<>();

        for (SourceDocument document : documents) {
            try {
                // 向有界线程池提交独立资料任务，任务内部会创建独立 extractionRunId 和运行状态
                aiBatchExtractionExecutor.execute(() -> runBatchExtraction(spaceId, document));
                acceptedDocumentIds.add(document.id());
            } catch (TaskRejectedException exception) {
                log.warn(
                        "批量 AI 抽取任务未受理，后台队列繁忙: spaceId={}, documentId={}",
                        spaceId,
                        document.id()
                );
                rejectedDocumentIds.add(document.id());
            }
        }

        return new AiExtractionBatchResponse(
                documents.size(),
                acceptedDocumentIds.size(),
                List.copyOf(acceptedDocumentIds),
                List.copyOf(rejectedDocumentIds)
        );
    }

    /**
     * 在线程池中运行一份来源资料的同步抽取核心流程，并将失败收口到该资料自己的运行记录。
     *
     * @param spaceId 知识空间标识
     * @param document 已完成前置校验的来源资料
     */
    private void runBatchExtraction(
            Long spaceId,
            SourceDocument document
    ) {
        try {
            // 使用现有无传输层抽取入口，复用分片、模型、证据校验、候选物化和失败持久化逻辑
            extractDocument(spaceId, document.id());
        } catch (RuntimeException exception) {
            log.debug(
                    "批量 AI 抽取已由独立运行记录收口失败: spaceId={}, documentId={}",
                    spaceId,
                    document.id(),
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
            Long spaceId,
            Long documentId,
            AiExtractionEventPublisher eventPublisher
    ) {
        Long extractionId = SnowflakeIdGenerator.nextId();
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

            // 使用确定性 Markdown 规则解析章节路径和原文偏移
            List<DocumentSection> sections = sectionParser.parse(document.contentText());

            // 按章节边界生成可追溯文本分片
            List<DocumentChunk> chunks = documentChunker.chunk(sections);

            // 在模型调用前幂等保存确定性章节和分片事实，模型失败时仍保留可重建结构
            structurePersistenceService.persist(document, sections, chunks);

            // 保存确定性章节和分片总数，处理中或失败后仍可恢复计划边界
            extractionRunRepository.plan(extractionId, sections.size(), chunks.size());

            // 获取当前已启用并完成配置的真实模型客户端
            AiExtractionClient extractionClient = extractionClientProvider.getIfAvailable();
            if (extractionClient == null) {
                throw new TipsException(
                        ErrorCode.AI_SERVICE_UNAVAILABLE,
                        "AI 服务未启用，请检查 AI_ENABLED 和 AI_API_KEY"
                );
            }

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

            // 通知前端开始执行独立的文档级全文摘要汇总阶段
            eventPublisher.publish(
                    AiExtractionStreamEvents.DOCUMENT_SUMMARY_STARTED,
                    new AiExtractionStreamEvents.DocumentSummaryStarted(
                            extractionId,
                            document.id(),
                            chunks.size(),
                            Instant.now()
                    )
            );

            // 使用一次独立模型调用汇总分片摘要；失败时保留已校验候选事实
            DocumentSummaryOutcome summaryOutcome = buildDocumentSummary(
                    extractionClient,
                    document,
                    chunkResults
            );
            eventPublisher.publish(
                    AiExtractionStreamEvents.DOCUMENT_SUMMARY_COMPLETED,
                    new AiExtractionStreamEvents.DocumentSummaryCompleted(
                            extractionId,
                            document.id(),
                            summaryOutcome.status(),
                            summaryOutcome.summary(),
                            summaryOutcome.errorMessage(),
                            Instant.now()
                    )
            );

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
                    summaryOutcome.summary(),
                    List.copyOf(chunkResults)
            );

            // 将已通过结构和证据校验的候选实体、关系和证据写入待审核图谱
            graphMaterializer.materialize(spaceId, document, response);

            // 序列化完整结果并保存，保证刷新页面后仍可重新查看
            extractionRunRepository.complete(
                    extractionId,
                    sections.size(),
                    chunks.size(),
                    summaryOutcome.summary(),
                    aiProperties.getSummaryPromptVersion(),
                    summaryOutcome.status(),
                    summaryOutcome.errorMessage(),
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
    public List<AiExtractionRunSummary> listExtractions(Long spaceId, Long documentId) {
        // 校验来源资料所属知识空间当前有效
        knowledgeSpaceService.requireActive(spaceId);

        // 确认来源资料存在且当前可访问
        requireDocument(spaceId, documentId);

        // 查询历史运行并转换为不带完整 JSON 的摘要
        return extractionRunRepository.findAllByDocument(spaceId, documentId).stream()
                .map(extractionRunEntityMapper::toSummary)
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
            Long spaceId,
            Long documentId,
            Long extractionId
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
        // 将抽取运行实体转换为不携带持久化细节的摘要
        return new AiExtractionRunDetail(extractionRunEntityMapper.toSummary(entity), result);
    }

    /**
     * 审核指定抽取运行中的一批候选关系，并写入图谱状态和审核历史。
     *
     * @param spaceId 知识空间标识
     * @param documentId 来源资料标识
     * @param extractionId 抽取运行标识
     * @param request 批量审核决定
     * @return 本次审核统计和当前剩余待审核数量
     */
    @Override
    public AiRelationReviewResponse reviewRelations(
            Long spaceId,
            Long documentId,
            Long extractionId,
            AiRelationReviewRequest request
    ) {
        // 校验知识空间和来源资料边界，防止跨空间审核抽取结果
        knowledgeSpaceService.requireActive(spaceId);
        SourceDocument document = requireDocument(spaceId, documentId);

        // 读取已完成的完整抽取结果，客户端只提交分片和关系顺序，不直接提交主体客体
        AiExtractionRunEntity entity = extractionRunRepository.findById(
                        spaceId,
                        documentId,
                        extractionId
                )
                .orElseThrow(() -> new TipsException(ErrorCode.NOT_FOUND, "AI 抽取记录不存在"));
        if (!"completed".equals(entity.getStatus()) || entity.getResultJson() == null) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "只有已完成的 AI 抽取结果可以审核");
        }

        AiDocumentExtractionResponse extraction = readResultJson(entity.getResultJson());
        // 按服务端保存的原始候选结果校验并持久化审核决定
        return graphMaterializer.reviewRelations(spaceId, document, extraction, request);
    }

    /**
     * 查询指定抽取运行已经持久化的候选关系审核状态。
     *
     * @param spaceId 知识空间标识
     * @param documentId 来源资料标识
     * @param extractionId 抽取运行标识
     * @return 已审核候选关系状态
     */
    @Override
    public List<AiRelationReviewState> listReviewStates(
            Long spaceId,
            Long documentId,
            Long extractionId
    ) {
        // 校验知识空间和来源资料边界，防止跨空间恢复审核状态
        knowledgeSpaceService.requireActive(spaceId);
        SourceDocument document = requireDocument(spaceId, documentId);
        AiExtractionRunEntity entity = extractionRunRepository.findById(
                        spaceId,
                        documentId,
                        extractionId
                )
                .orElseThrow(() -> new TipsException(ErrorCode.NOT_FOUND, "AI 抽取记录不存在"));
        if (!"completed".equals(entity.getStatus()) || entity.getResultJson() == null) {
            return List.of();
        }

        // 根据服务端保存的完整候选结果和图谱状态恢复弹窗审核显示
        return graphMaterializer.listReviewStates(spaceId, readResultJson(entity.getResultJson()));
    }

    private AiExtractionRunEntity createProcessingRun(
            Long extractionId,
            Long spaceId,
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
        entity.setDocumentSummaryPromptVersion(aiProperties.getSummaryPromptVersion());
        entity.setDocumentSummaryStatus("not_started");
        entity.setCreatedAt(createdAt.toString());
        return entity;
    }

    private SourceDocument requireDocument(Long spaceId, Long documentId) {
        return sourceDocumentRepository.findById(spaceId, documentId)
                .orElseThrow(() -> new TipsException(ErrorCode.NOT_FOUND, "来源资料不存在"));
    }

    /**
     * 使用一次模型调用把按章节排列的分片摘要汇总为自然的全文摘要。
     *
     * @param extractionClient 已启用的 AI 客户端
     * @param document 来源资料
     * @param chunkResults 已通过结构和证据校验的分片抽取结果
     * @return 全文摘要结果及其独立状态；失败不抛出到整次抽取流程
     */
    private DocumentSummaryOutcome buildDocumentSummary(
            AiExtractionClient extractionClient,
            SourceDocument document,
            List<AiChunkExtractionResult> chunkResults
    ) {
        AiDocumentSummaryRequest request = new AiDocumentSummaryRequest(
                document.id(),
                document.name(),
                document.documentType().getValue(),
                chunkResults.stream()
                        .map(chunk -> new AiDocumentSummaryRequest.ChunkSummary(
                                chunk.chunkId(),
                                chunk.sectionPath(),
                                chunk.extraction().summary().replaceAll("\\s+", " ").strip()
                        ))
                        .toList()
        );

        try {
            // 调用领域客户端执行全文摘要 Reduce，不把原始全文再次发送给模型
            String summary = extractionClient.summarize(request);
            String normalizedSummary = summary == null
                    ? ""
                    : summary.replaceAll("\\s+", " ").strip();
            if (normalizedSummary.isEmpty()) {
                return new DocumentSummaryOutcome(null, "failed", "AI 未生成有效的全文摘要");
            }
            if (normalizedSummary.length() > DOCUMENT_SUMMARY_MAX_LENGTH) {
                return new DocumentSummaryOutcome(null, "failed", "全文摘要超过 160 个字符");
            }
            return new DocumentSummaryOutcome(normalizedSummary, "completed", null);
        } catch (RuntimeException exception) {
            log.warn(
                    "文档级全文摘要生成失败，保留已校验候选事实: documentId={}",
                    document.id(),
                    exception
            );
            return new DocumentSummaryOutcome(null, "failed", "AI 全文摘要生成失败，已保留候选事实");
        }
    }

    private record DocumentSummaryOutcome(
            String summary,
            String status,
            String errorMessage
    ) {
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
            Long extractionId,
            Long documentId,
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
            Long extractionId,
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
                    "AI 返回的结构化结果未通过业务或证据校验"
            );
        } catch (RetriableException exception) {
            log.warn(
                    "AI 上游服务暂时不可用: documentId={}, chunkId={}",
                    document.id(),
                    chunk.chunkId(),
                    exception
            );
            throw new TipsException(
                    ErrorCode.AI_SERVICE_UNAVAILABLE,
                    "AI 上游服务暂时不可用，请稍后重试"
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

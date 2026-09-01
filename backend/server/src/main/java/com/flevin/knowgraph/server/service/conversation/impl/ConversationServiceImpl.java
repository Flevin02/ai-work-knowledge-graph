package com.flevin.knowgraph.server.service.conversation.impl;

import com.flevin.knowgraph.common.enums.ErrorCode;
import com.flevin.knowgraph.common.exception.TipsException;
import com.flevin.knowgraph.server.config.properties.RagProperties;
import com.flevin.knowgraph.server.model.ai.rag.DocumentChunk;
import com.flevin.knowgraph.server.model.ai.rag.DocumentChunkFact;
import com.flevin.knowgraph.server.model.conversation.ConversationAnswerCitation;
import com.flevin.knowgraph.server.model.conversation.ConversationAnswerRequest;
import com.flevin.knowgraph.server.model.conversation.ConversationAnswerResult;
import com.flevin.knowgraph.server.model.conversation.ConversationDetailResponse;
import com.flevin.knowgraph.server.model.conversation.ConversationMessageResponse;
import com.flevin.knowgraph.server.model.conversation.ConversationResponse;
import com.flevin.knowgraph.server.model.conversation.CreateConversationRequest;
import com.flevin.knowgraph.server.model.conversation.MessageCitationResponse;
import com.flevin.knowgraph.server.model.conversation.SubmitConversationMessageRequest;
import com.flevin.knowgraph.server.model.document.SourceDocument;
import com.flevin.knowgraph.server.repository.conversation.ConversationMessageRepository;
import com.flevin.knowgraph.server.repository.conversation.ConversationRepository;
import com.flevin.knowgraph.server.repository.conversation.MessageCitationRepository;
import com.flevin.knowgraph.server.repository.document.DocumentChunkRepository;
import com.flevin.knowgraph.server.repository.document.SourceDocumentRepository;
import com.flevin.knowgraph.server.repository.entity.ConversationEntity;
import com.flevin.knowgraph.server.repository.entity.ConversationMessageEntity;
import com.flevin.knowgraph.server.repository.entity.MessageCitationEntity;
import com.flevin.knowgraph.server.repository.space.KnowledgeSpaceRepository;
import com.flevin.knowgraph.server.service.conversation.ConversationAnswerClient;
import com.flevin.knowgraph.server.service.conversation.ConversationAnswerInvalidOutputException;
import com.flevin.knowgraph.server.service.conversation.ConversationService;
import com.flevin.knowgraph.server.util.SnowflakeIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 知识空间内只读问答固定 Pipeline 实现。
 *
 * <p>本服务负责会话与消息的事实持久化、空间隔离、范围文档分片上下文组装、
 * 供应商无关回答客户端调用和引用逐字反查。回答与引用的事实规则与文档关联
 * 一致：客户端只输出局部 chunkId，数据库标识、引用校验和证据状态判定始终
 * 由服务端完成；未启用生产客户端时回答记录为失败状态。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationServiceImpl implements ConversationService {

    /** 与生产提示词资源对应的固定回答 Prompt 版本。 */
    private static final String PROMPT_VERSION = "conversation-answer-v1";

    /** 与领域回答 record 对应的固定结构 Schema 版本。 */
    private static final String SCHEMA_VERSION = "conversation-answer-schema-v1";

    /** 未标题会话的默认标题。 */
    private static final String DEFAULT_TITLE = "问答会话";

    private final KnowledgeSpaceRepository knowledgeSpaceRepository;
    private final SourceDocumentRepository sourceDocumentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final ConversationRepository conversationRepository;
    private final ConversationMessageRepository conversationMessageRepository;
    private final MessageCitationRepository messageCitationRepository;
    private final RagProperties ragProperties;
    private final ObjectProvider<ConversationAnswerClient> answerClientProvider;

    @Override
    public ConversationResponse createConversation(
            Long spaceId,
            CreateConversationRequest request
    ) {
        // 校验空间存在且有效，保证会话只能建立在有效知识空间内
        knowledgeSpaceRepository.findActiveById(spaceId)
                .orElseThrow(() -> new TipsException(ErrorCode.NOT_FOUND, "知识空间不存在"));

        // 存在文档范围时校验其归属当前空间，禁止跨空间圈定问答范围
        if (request.scopeDocumentId() != null) {
            sourceDocumentRepository.findById(spaceId, request.scopeDocumentId())
                    .orElseThrow(() -> new TipsException(ErrorCode.NOT_FOUND, "来源资料不存在或已删除"));
        }

        // 生成会话事实并持久化；标题为空时使用稳定默认标题
        Instant now = Instant.now();
        ConversationEntity entity = new ConversationEntity();
        entity.setId(SnowflakeIdGenerator.nextId());
        entity.setSpaceId(spaceId);
        entity.setTitle(resolveTitle(request.title()));
        entity.setScopeSourceDocumentId(request.scopeDocumentId());
        entity.setStatus("active");
        entity.setCreatedAt(now.toString());
        entity.setUpdatedAt(now.toString());
        conversationRepository.save(entity);

        return toConversationResponse(entity);
    }

    @Override
    public ConversationDetailResponse getConversation(
            Long spaceId,
            Long conversationId
    ) {
        // 按空间隔离恢复会话，跨空间或软删除会话一律不可见
        ConversationEntity conversation = conversationRepository.findActiveById(spaceId, conversationId)
                .orElseThrow(() -> new TipsException(ErrorCode.NOT_FOUND, "问答会话不存在"));

        // 恢复全部消息和引用；引用按消息一次批量读取，避免逐消息查询
        List<ConversationMessageEntity> messages =
                conversationMessageRepository.findByConversation(spaceId, conversationId);
        Map<Long, List<MessageCitationEntity>> citationsByMessage = loadCitationsByMessage(
                spaceId,
                messages
        );

        // 来源名称和失效标记按引用涉及的文档一次批量恢复，避免逐引用查询
        Map<Long, SourceDocument> citationDocuments = loadCitationDocuments(
                spaceId,
                citationsByMessage.values().stream().flatMap(List::stream).toList()
        );

        // 组装带来源失效标记的完整会话详情
        List<ConversationMessageResponse> messageResponses = messages.stream()
                .map(message -> toMessageResponse(
                        message,
                        citationsByMessage.getOrDefault(message.getId(), List.of()),
                        citationDocuments
                ))
                .toList();
        return new ConversationDetailResponse(toConversationResponse(conversation), messageResponses);
    }

    @Override
    public ConversationMessageResponse submitMessage(
            Long spaceId,
            Long conversationId,
            SubmitConversationMessageRequest request
    ) {
        // 校验空间和会话归属，保证问答只能发生在有效空间的有效会话内
        knowledgeSpaceRepository.findActiveById(spaceId)
                .orElseThrow(() -> new TipsException(ErrorCode.NOT_FOUND, "知识空间不存在"));
        ConversationEntity conversation = conversationRepository.findActiveById(spaceId, conversationId)
                .orElseThrow(() -> new TipsException(ErrorCode.NOT_FOUND, "问答会话不存在"));

        // 先持久化用户消息；无论回答是否成功，用户输入始终保留为会话事实
        Instant now = Instant.now();
        ConversationMessageEntity userMessage = new ConversationMessageEntity();
        userMessage.setId(SnowflakeIdGenerator.nextId());
        userMessage.setSpaceId(spaceId);
        userMessage.setConversationId(conversationId);
        userMessage.setRole("user");
        userMessage.setContent(request.question());
        userMessage.setStatus("completed");
        userMessage.setCitationCount(0);
        userMessage.setCitationFailureCount(0);
        userMessage.setCreatedAt(now.toString());
        conversationMessageRepository.save(userMessage);

        // 组装范围文档分片上下文；未圈定文档时上下文为空，回答按证据不足处理
        SourceDocument scopeDocument = resolveScopeDocument(conversation);
        List<DocumentChunkFact> chunkFacts = loadScopeChunks(conversation, scopeDocument);
        List<DocumentChunk> contextChunks = chunkFacts.stream()
                .map(this::toContextChunk)
                .toList();

        ConversationMessageEntity answerMessage = generateAnswerMessage(
                conversation,
                request.question(),
                scopeDocument,
                chunkFacts,
                contextChunks
        );

        // 回答完成后刷新会话更新时间，保证会话列表排序语义正确
        touchConversation(conversation, now.toString());

        // 单条消息的引用来源文档按需恢复，用于计算来源失效标记和展示名称
        List<MessageCitationEntity> citations = loadCitations(spaceId, answerMessage.getId());
        Map<Long, SourceDocument> citationDocuments = loadCitationDocuments(spaceId, citations);
        return toMessageResponse(answerMessage, citations, citationDocuments);
    }

    @Override
    public ConversationMessageResponse getMessage(
            Long spaceId,
            Long conversationId,
            Long messageId
    ) {
        // 会话与消息双重校验归属，防止跨会话或跨空间读取消息
        conversationRepository.findActiveById(spaceId, conversationId)
                .orElseThrow(() -> new TipsException(ErrorCode.NOT_FOUND, "问答会话不存在"));
        ConversationMessageEntity message = conversationMessageRepository
                .findByMessageId(spaceId, conversationId, messageId)
                .orElseThrow(() -> new TipsException(ErrorCode.NOT_FOUND, "问答消息不存在"));

        Map<Long, SourceDocument> citationDocuments = loadCitationDocuments(
                spaceId,
                loadCitations(spaceId, messageId)
        );
        return toMessageResponse(message, loadCitations(spaceId, messageId), citationDocuments);
    }

    /**
     * 生成并持久化助手回答消息，覆盖客户端未启用、调用失败和引用校验三类边界。
     *
     * @param conversation 当前会话实体
     * @param question 用户问题
     * @param scopeDocument 可选范围文档；未圈定时为 null
     * @param chunkFacts 服务端召回的分片事实
     * @param contextChunks 提供给客户端的上下文分片
     * @return 已持久化的回答消息实体
     */
    private ConversationMessageEntity generateAnswerMessage(
            ConversationEntity conversation,
            String question,
            SourceDocument scopeDocument,
            List<DocumentChunkFact> chunkFacts,
            List<DocumentChunk> contextChunks
    ) {
        Long spaceId = conversation.getSpaceId();
        Instant createdAt = Instant.now();

        // 获取当前环境显式提供的问答客户端；AI 未启用或配置不足时记录失败事实
        ConversationAnswerClient answerClient = answerClientProvider.getIfAvailable();
        if (answerClient == null) {
            return persistFailedAnswer(spaceId, conversation.getId(), createdAt,
                    "answer_client_unavailable", "有据问答服务未启用");
        }

        // 预生成回答标识，供引用落库前关联消息，不依赖数据库自增
        Long answerId = SnowflakeIdGenerator.nextId();

        // 调用供应商无关客户端生成回答候选；客户端异常不吞掉，落为稳定失败状态
        ConversationAnswerResult result;
        try {
            // 组装不包含存储路径、数据库标识或其他空间资料的安全问答请求
            result = answerClient.answer(new ConversationAnswerRequest(question, contextChunks));
        } catch (ConversationAnswerInvalidOutputException exception) {
            // 结构化输出不合格时记录独立类别，日志不携带可能含模型原文的解析异常
            log.warn(
                    "有据问答模型输出无效: conversationId={}, spaceId={}",
                    conversation.getId(),
                    spaceId
            );
            return persistFailedAnswer(spaceId, conversation.getId(), createdAt,
                    "answer_invalid_output", "有据问答服务返回无效结果");
        } catch (RuntimeException exception) {
            // 模型异常只记录安全类型和业务定位，不输出可能携带响应正文的异常消息
            log.warn(
                    "有据问答客户端调用失败: conversationId={}, spaceId={}, exceptionType={}",
                    conversation.getId(),
                    spaceId,
                    exception.getClass().getSimpleName()
            );
            return persistFailedAnswer(spaceId, conversation.getId(), createdAt,
                    "answer_failed", "有据问答服务返回失败");
        }

        // 引用逐条逐字反查：客户端引用的分片必须来自本次上下文且原文逐字存在
        List<MessageCitationEntity> verifiedCitations = verifyCitations(
                spaceId,
                answerId,
                result.citations(),
                chunkFacts,
                scopeDocument
            );
        int failureCount = deduplicatedCandidates(result.citations()).size() - verifiedCitations.size();

        // 证据状态只描述服务端校验结果，不采信客户端自我声明
        String groundingStatus = resolveGroundingStatus(verifiedCitations.size(), failureCount);

        ConversationMessageEntity answer = new ConversationMessageEntity();
        answer.setId(answerId);
        answer.setSpaceId(spaceId);
        answer.setConversationId(conversation.getId());
        answer.setRole("assistant");
        answer.setContent(result.answer());
        answer.setStatus("completed");
        answer.setGroundingStatus(groundingStatus);
        answer.setAnswerClient(answerClient.clientId());
        answer.setPromptVersion(PROMPT_VERSION);
        answer.setSchemaVersion(SCHEMA_VERSION);
        answer.setCitationCount(verifiedCitations.size());
        answer.setCitationFailureCount(failureCount);
        answer.setDurationMs(java.time.Duration.between(createdAt, Instant.now()).toMillis());
        answer.setCreatedAt(createdAt.toString());

        // 回答与引用在同一事务内原子落库，避免只有回答没有引用的中间态
        conversationMessageRepository.saveAnswerWithCitations(answer, verifiedCitations);
        return answer;
    }

    /**
     * 持久化一条失败回答消息。
     *
     * @param spaceId 知识空间标识
     * @param conversationId 会话标识
     * @param createdAt 消息创建时间
     * @param errorCategory 稳定错误类别
     * @param errorMessage 面向用户的稳定错误摘要
     * @return 已持久化的失败消息实体
     */
    private ConversationMessageEntity persistFailedAnswer(
            Long spaceId,
            Long conversationId,
            Instant createdAt,
            String errorCategory,
            String errorMessage
    ) {
        ConversationMessageEntity failed = new ConversationMessageEntity();
        failed.setId(SnowflakeIdGenerator.nextId());
        failed.setSpaceId(spaceId);
        failed.setConversationId(conversationId);
        failed.setRole("assistant");
        failed.setContent("");
        failed.setStatus("failed");
        failed.setErrorCategory(errorCategory);
        failed.setErrorMessage(errorMessage);
        failed.setCitationCount(0);
        failed.setCitationFailureCount(0);
        failed.setCreatedAt(createdAt.toString());
        conversationMessageRepository.save(failed);
        return failed;
    }

    /**
     * 按分片集合逐字反查候选引用，返回带数据库标识的已验证引用实体。
     *
     * <p>候选引用先按分片和原文去重，再逐条校验：分片必须来自本次上下文、
     * 原文必须逐字存在、提供的偏移必须与原文片段实际位置一致；校验失败的
     * 引用被移除并计入引用失败数量，不会静默修正文案后当作原文。</p>
     *
     * @param spaceId 知识空间标识
     * @param candidates 客户端输出的候选引用
     * @param chunkFacts 服务端召回的分片事实
     * @param scopeDocument 可选范围文档，用于记录引用时的内容指纹
     * @return 通过逐字反查的引用实体列表
     */
    private List<MessageCitationEntity> verifyCitations(
            Long spaceId,
            Long answerId,
            List<ConversationAnswerCitation> candidates,
            List<DocumentChunkFact> chunkFacts,
            SourceDocument scopeDocument
    ) {
        // 上下文为空时不存在任何可验证引用，直接返回空列表
        if (chunkFacts.isEmpty() || candidates.isEmpty()) {
            return List.of();
        }

        // 按分片标识建立查询索引，避免逐候选扫描分片集合
        Map<String, DocumentChunkFact> factsByChunkId = chunkFacts.stream()
                .collect(Collectors.toMap(DocumentChunkFact::chunkId, Function.identity(), (first, second) -> first));

        List<MessageCitationEntity> verified = new ArrayList<>();
        int order = 1;
        for (ConversationAnswerCitation candidate : deduplicatedCandidates(candidates)) {
            DocumentChunkFact fact = factsByChunkId.get(candidate.chunkId());
            if (fact == null || !isQuoteVerbatim(candidate, fact)) {
                // 引用不在服务端召回集合内或原文反查失败：保留失败计数，不落库
                continue;
            }
            MessageCitationEntity entity = new MessageCitationEntity();
            entity.setId(SnowflakeIdGenerator.nextId());
            entity.setSpaceId(spaceId);
            entity.setMessageId(answerId);
            entity.setSourceDocumentId(fact.sourceDocumentId());
            entity.setDocumentContentHash(scopeDocument == null ? "" : scopeDocument.contentHash());
            entity.setChunkRecordId(fact.id());
            entity.setChunkId(fact.chunkId());
            entity.setSectionPath(fact.sectionPath());
            entity.setQuote(candidate.quote());
            entity.setStartOffset(candidate.startOffset());
            entity.setEndOffset(candidate.endOffset());
            entity.setCitationOrder(order++);
            entity.setValidationStatus("verified");
            entity.setCreatedAt(Instant.now().toString());
            verified.add(entity);
        }
        return verified;
    }

    /**
     * 判断候选引用的原文片段是否在分片原文中逐字存在且偏移一致。
     *
     * @param candidate 候选引用
     * @param fact 分片事实
     * @return 原文逐字反查是否通过
     */
    private boolean isQuoteVerbatim(
            ConversationAnswerCitation candidate,
            DocumentChunkFact fact
    ) {
        String quote = candidate.quote();
        String content = fact.contentText();
        if (quote == null || quote.isBlank() || !content.contains(quote)) {
            return false;
        }

        // 提供偏移时必须与原文片段实际位置完全一致，防止错位引用
        Integer start = candidate.startOffset();
        Integer end = candidate.endOffset();
        if (start == null && end == null) {
            return true;
        }
        return start != null && end != null
                && start >= 0
                && end <= content.length()
                && start < end
                && content.substring(start, end).equals(quote);
    }

    /**
     * 按分片和原文去重候选引用，保持首次出现的顺序。
     *
     * @param candidates 客户端输出的候选引用
     * @return 去重后的候选引用列表
     */
    private List<ConversationAnswerCitation> deduplicatedCandidates(
            List<ConversationAnswerCitation> candidates
    ) {
        // 相同分片且相同原文的重复引用只保留首条，防止引用数量虚增
        Map<String, ConversationAnswerCitation> unique = new LinkedHashMap<>();
        candidates.stream()
                .filter(Objects::nonNull)
                .forEach(candidate -> unique.putIfAbsent(
                        candidate.chunkId() + "\n" + candidate.quote(),
                        candidate
                ));
        return List.copyOf(unique.values());
    }

    /**
     * 根据通过校验的引用数量判定回答证据状态。
     *
     * @param verifiedCount 通过逐字反查的引用数量
     * @param failureCount 被移除的引用数量
     * @return grounded、partially_grounded 或 insufficient_evidence
     */
    private String resolveGroundingStatus(
            int verifiedCount,
            int failureCount
    ) {
        // 无任何可验证引用时必须给出明确证据不足状态，不能伪装为可信回答
        if (verifiedCount == 0) {
            return "insufficient_evidence";
        }
        return failureCount > 0 ? "partially_grounded" : "grounded";
    }

    /**
     * 读取范围文档事实；会话未圈定文档时返回 null。
     *
     * @param conversation 当前会话实体
     * @return 范围文档领域模型
     */
    private SourceDocument resolveScopeDocument(ConversationEntity conversation) {
        if (conversation.getScopeSourceDocumentId() == null) {
            return null;
        }
        // 圈定文档被删除时返回 null，回答按上下文为空处理而不是报错
        return sourceDocumentRepository.findById(
                conversation.getSpaceId(),
                conversation.getScopeSourceDocumentId()
        ).orElse(null);
    }

    /**
     * 读取范围文档当前分片策略版本的全部分片事实。
     *
     * @param conversation 当前会话实体
     * @param scopeDocument 可选范围文档
     * @return 按文档顺序排列的分片事实
     */
    private List<DocumentChunkFact> loadScopeChunks(
            ConversationEntity conversation,
            SourceDocument scopeDocument
    ) {
        if (scopeDocument == null) {
            return List.of();
        }
        // 使用当前固定分片策略版本读取分片，保证与索引和证据定位一致
        return documentChunkRepository.findByDocument(
                conversation.getSpaceId(),
                scopeDocument.id(),
                ragProperties.getChunkStrategyVersion()
        );
    }

    /**
     * 将分片事实转换为不包含数据库标识和指纹的上下文分片。
     *
     * @param fact 分片事实
     * @return 提供给客户端的上下文分片
     */
    private DocumentChunk toContextChunk(DocumentChunkFact fact) {
        // 上下文只暴露章节路径、原文和偏移，不携带数据库主键或内容指纹
        return new DocumentChunk(
                fact.chunkId(),
                fact.sectionId(),
                fact.sectionPath(),
                fact.ordinal(),
                fact.contentText(),
                fact.startOffset(),
                fact.endOffset()
        );
    }

    /**
     * 刷新会话最近更新时间。
     *
     * @param conversation 当前会话实体
     * @param updatedAt 新的更新时间，ISO-8601 UTC 字符串
     */
    private void touchConversation(
            ConversationEntity conversation,
            String updatedAt
    ) {
        conversationRepository.touch(conversation.getSpaceId(), conversation.getId(), updatedAt);
    }

    /**
     * 批量恢复多条消息的引用并按消息标识分组。
     *
     * @param spaceId 知识空间标识
     * @param messages 消息实体列表
     * @return 按消息标识分组的引用实体
     */
    private Map<Long, List<MessageCitationEntity>> loadCitationsByMessage(
            Long spaceId,
            List<ConversationMessageEntity> messages
    ) {
        // 仅助手消息可能存在引用，一次 IN 查询恢复后按消息标识分组
        List<Long> assistantMessageIds = messages.stream()
                .filter(message -> "assistant".equals(message.getRole()))
                .map(ConversationMessageEntity::getId)
                .toList();
        return messageCitationRepository.findByMessageIds(spaceId, assistantMessageIds).stream()
                .collect(Collectors.groupingBy(
                        MessageCitationEntity::getMessageId,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
    }

    /**
     * 恢复单条消息的全部引用。
     *
     * @param spaceId 知识空间标识
     * @param messageId 回答消息标识
     * @return 引用实体列表
     */
    private List<MessageCitationEntity> loadCitations(
            Long spaceId,
            Long messageId
    ) {
        return messageCitationRepository.findByMessage(spaceId, messageId);
    }

    /**
     * 将会话实体转换为响应元数据。
     *
     * @param entity 会话持久化实体
     * @return 会话响应
     */
    private ConversationResponse toConversationResponse(ConversationEntity entity) {
        return new ConversationResponse(
                entity.getId(),
                entity.getSpaceId(),
                entity.getTitle(),
                entity.getScopeSourceDocumentId(),
                entity.getStatus(),
                Instant.parse(entity.getCreatedAt()),
                Instant.parse(entity.getUpdatedAt())
        );
    }

    /**
     * 将消息实体和引用实体转换为响应。
     *
     * @param message 消息持久化实体
     * @param citations 引用实体列表
     * @param citationDocuments 引用涉及的来源文档，按文档标识索引
     * @return 消息响应
     */
    private ConversationMessageResponse toMessageResponse(
            ConversationMessageEntity message,
            List<MessageCitationEntity> citations,
            Map<Long, SourceDocument> citationDocuments
    ) {
        List<MessageCitationResponse> citationResponses = citations.stream()
                .map(citation -> toCitationResponse(citation, citationDocuments))
                .toList();
        return new ConversationMessageResponse(
                message.getId(),
                message.getConversationId(),
                message.getRole(),
                message.getContent(),
                message.getStatus(),
                message.getGroundingStatus(),
                message.getErrorCategory(),
                message.getErrorMessage(),
                message.getAnswerClient(),
                message.getPromptVersion(),
                message.getSchemaVersion(),
                message.getCitationCount(),
                message.getCitationFailureCount(),
                message.getDurationMs(),
                Instant.parse(message.getCreatedAt()),
                citationResponses
        );
    }

    /**
     * 将引用实体转换为响应并计算来源失效标记。
     *
     * <p>来源失效按读取侧计算：引用记录的内容指纹与当前来源文档指纹不一致，
     * 或来源文档已不存在时标记 stale；历史引用本身保留，不静默替换为新版本。</p>
     *
     * @param citation 引用持久化实体
     * @param citationDocuments 引用涉及的来源文档映射
     * @return 引用响应
     */
    private MessageCitationResponse toCitationResponse(
            MessageCitationEntity citation,
            Map<Long, SourceDocument> citationDocuments
    ) {
        // 来源文档不存在或内容指纹已变化时标记失效，历史引用保留原版本
        SourceDocument document = citationDocuments.get(citation.getSourceDocumentId());
        boolean sourceStale = document == null
                || !document.contentHash().equals(citation.getDocumentContentHash());
        return new MessageCitationResponse(
                citation.getId(),
                citation.getMessageId(),
                citation.getSourceDocumentId(),
                document == null ? null : document.name(),
                sourceStale,
                citation.getChunkRecordId(),
                citation.getChunkId(),
                citation.getSectionPath(),
                citation.getQuote(),
                citation.getStartOffset(),
                citation.getEndOffset(),
                citation.getCitationOrder(),
                sourceStale ? "stale" : citation.getValidationStatus()
        );
    }

    /**
     * 批量恢复引用涉及的来源文档，避免逐引用查询形成 N+1。
     *
     * @param spaceId 知识空间标识
     * @param citations 引用实体列表
     * @return 按文档标识索引的来源文档
     */
    private Map<Long, SourceDocument> loadCitationDocuments(
            Long spaceId,
            List<MessageCitationEntity> citations
    ) {
        // 去重后逐文档读取；当前问答引用的文档数量有限，空间全量读取反而不必要
        return citations.stream()
                .map(MessageCitationEntity::getSourceDocumentId)
                .distinct()
                .flatMap(documentId -> sourceDocumentRepository.findById(spaceId, documentId).stream())
                .collect(Collectors.toMap(SourceDocument::id, Function.identity(), (first, second) -> first));
    }

    /**
     * 解析会话标题；输入为空白时使用稳定默认标题。
     *
     * @param title 用户输入标题
     * @return 会话标题
     */
    private String resolveTitle(String title) {
        return title == null || title.isBlank() ? DEFAULT_TITLE : title.trim();
    }
}

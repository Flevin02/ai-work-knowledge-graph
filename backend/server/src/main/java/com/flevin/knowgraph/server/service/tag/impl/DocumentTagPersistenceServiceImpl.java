package com.flevin.knowgraph.server.service.tag.impl;

import com.flevin.knowgraph.common.enums.ErrorCode;
import com.flevin.knowgraph.common.exception.TipsException;
import com.flevin.knowgraph.server.model.document.SourceDocument;
import com.flevin.knowgraph.server.model.tag.DocumentTag;
import com.flevin.knowgraph.server.model.tag.DocumentTagEvidence;
import com.flevin.knowgraph.server.model.tag.KnowledgeTag;
import com.flevin.knowgraph.server.repository.document.SourceDocumentRepository;
import com.flevin.knowgraph.server.repository.space.KnowledgeSpaceRepository;
import com.flevin.knowgraph.server.repository.tag.DocumentTagRepository;
import com.flevin.knowgraph.server.service.tag.DocumentTagPersistenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 文档标签持久化服务实现，统一执行轻量规范化、来源状态、版本幂等和逐字证据校验。
 */
@Service
@RequiredArgsConstructor
public class DocumentTagPersistenceServiceImpl implements DocumentTagPersistenceService {

    private static final String ACTIVE_STATUS = "active";
    private static final String AI_SOURCE_TYPE = "ai";
    private static final String USER_SOURCE_TYPE = "user";
    private static final String SUGGESTED_STATUS = "suggested";
    private static final String CONFIRMED_STATUS = "confirmed";
    private static final int MIN_TAG_LENGTH = 2;
    private static final int MAX_TAG_LENGTH = 24;
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile(
            "\\s+",
            Pattern.UNICODE_CHARACTER_CLASS
    );
    private static final String KEY_SEPARATOR = "\u001f";

    private final KnowledgeSpaceRepository knowledgeSpaceRepository;
    private final SourceDocumentRepository sourceDocumentRepository;
    private final DocumentTagRepository documentTagRepository;

    /**
     * 在同一事务中幂等保存一条 AI 候选标签及其全部逐字证据。
     *
     * @param tag 待创建或按规范化键复用的空间标签定义
     * @param documentTag 初始状态必须为 suggested 的 AI 文档标签关系
     * @param evidences 当前来源资料中能够逐字反查的标签证据
     * @return 新保存或按冻结幂等键复用的文档标签关系
     */
    @Override
    @Transactional
    public DocumentTag saveAiSuggestion(
            KnowledgeTag tag,
            DocumentTag documentTag,
            List<DocumentTagEvidence> evidences
    ) {
        // 校验 AI 来源、建议态和版本快照，防止模型结果越过人工审核边界
        validateAiSuggestion(documentTag, evidences);

        // 校验知识空间与来源资料并取得逐字证据事实源
        SourceDocument sourceDocument = requireSourceDocument(documentTag);

        // 规范化或复用空间标签定义，避免大小写和空格差异产生重复标签
        KnowledgeTag persistedTag = resolveTag(tag, documentTag);

        // 按内容指纹、规范化标签和 Prompt/Schema 版本生成稳定候选
        DocumentTag preparedTag = prepareDocumentTag(documentTag, persistedTag, sourceDocument);

        // 在写入前校验全部证据，任一证据失败时回滚标签定义和文档标签关系
        List<DocumentTagEvidence> preparedEvidences = prepareEvidences(
                preparedTag,
                sourceDocument,
                evidences
        );

        // 相同输入和版本的重复运行复用既有候选，不重复写入标签或证据
        DocumentTag existingTag = documentTagRepository.findDocumentTagByKey(
                preparedTag.spaceId(),
                preparedTag.documentTagKey()
        ).orElse(null);
        if (existingTag != null) {
            return existingTag;
        }

        // 保存通过全部领域校验的 AI 候选标签
        documentTagRepository.saveDocumentTag(preparedTag);

        // 批量遍历已校验证据并写入同一事务
        preparedEvidences.forEach(documentTagRepository::saveEvidence);
        return preparedTag;
    }

    /**
     * 幂等保存用户手工标签，文档标签关系保存后直接为 confirmed。
     *
     * @param tag 待创建或按规范化键复用的空间标签定义
     * @param documentTag 来源必须为 user、初始状态必须为 confirmed 的文档标签关系
     * @return 新保存或按手工标签幂等键复用的文档标签关系
     */
    @Override
    @Transactional
    public DocumentTag saveUserTag(
            KnowledgeTag tag,
            DocumentTag documentTag
    ) {
        // 校验用户来源和直接确认边界，拒绝携带模型置信度或版本字段
        validateUserTag(documentTag);

        // 校验知识空间与来源资料并取得当前内容指纹事实源
        SourceDocument sourceDocument = requireSourceDocument(documentTag);

        // 规范化或复用空间标签定义，保证手工与 AI 标签共享同一字典
        KnowledgeTag persistedTag = resolveTag(tag, documentTag);

        // 使用空 Prompt/Schema 槽位生成手工标签稳定幂等键
        DocumentTag preparedTag = prepareDocumentTag(documentTag, persistedTag, sourceDocument);

        // 重复创建同一内容版本的手工标签时复用既有关系
        DocumentTag existingTag = documentTagRepository.findDocumentTagByKey(
                preparedTag.spaceId(),
                preparedTag.documentTagKey()
        ).orElse(null);
        if (existingTag != null) {
            return existingTag;
        }

        // 保存直接确认的用户手工标签关系
        documentTagRepository.saveDocumentTag(preparedTag);
        return preparedTag;
    }

    /**
     * 查询指定来源资料的全部文档标签状态，用于后续审核和页面恢复。
     *
     * @param spaceId 知识空间标识
     * @param sourceDocumentId 来源资料标识
     * @return 文档标签关系列表
     */
    @Override
    @Transactional(readOnly = true)
    public List<DocumentTag> listDocumentTags(
            String spaceId,
            String sourceDocumentId
    ) {
        // 校验空间内有效来源资料，阻断跨空间或已删除资料读取
        requireSourceDocument(spaceId, sourceDocumentId);

        // 批量查询当前资料的全部标签状态，避免逐标签访问数据库
        return documentTagRepository.findAllByDocument(spaceId, sourceDocumentId);
    }

    /**
     * 查询一条文档标签关系的全部逐字证据。
     *
     * @param spaceId 知识空间标识
     * @param documentTagId 文档标签关系标识
     * @return 标签证据列表
     */
    @Override
    @Transactional(readOnly = true)
    public List<DocumentTagEvidence> listEvidence(
            String spaceId,
            String documentTagId
    ) {
        // 校验文档标签关系存在且属于当前空间
        documentTagRepository.findDocumentTagById(spaceId, documentTagId)
                .orElseThrow(() -> new TipsException(ErrorCode.NOT_FOUND, "文档标签不存在"));

        // 批量查询文档标签下的全部已校验证据
        return documentTagRepository.findEvidenceByDocumentTag(spaceId, documentTagId);
    }

    /**
     * 校验 AI 候选标签的来源、初始状态、版本和证据形状。
     *
     * @param documentTag AI 文档标签关系
     * @param evidences AI 标签证据列表
     */
    private void validateAiSuggestion(
            DocumentTag documentTag,
            List<DocumentTagEvidence> evidences
    ) {
        requireDocumentTag(documentTag);
        if (!AI_SOURCE_TYPE.equals(documentTag.sourceType())) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "AI 候选标签来源必须为 ai");
        }
        if (!SUGGESTED_STATUS.equals(documentTag.status())) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "AI 候选标签初始状态必须为 suggested");
        }
        if (documentTag.confidence() == null
                || documentTag.confidence() < 0
                || documentTag.confidence() > 1) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "AI 候选标签置信度必须在 0 到 1 之间");
        }
        if (isBlank(documentTag.promptVersion()) || isBlank(documentTag.schemaVersion())) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "AI 候选标签必须记录 Prompt 和 Schema 版本");
        }
        if (isBlank(documentTag.extractionRunId())) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "AI 候选标签必须记录抽取运行标识");
        }
        if (evidences == null || evidences.isEmpty()) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "AI 候选标签必须包含可追溯证据");
        }
    }

    /**
     * 校验用户手工标签的来源、确认状态和非模型字段边界。
     *
     * @param documentTag 用户手工文档标签关系
     */
    private void validateUserTag(DocumentTag documentTag) {
        requireDocumentTag(documentTag);
        if (!USER_SOURCE_TYPE.equals(documentTag.sourceType())) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "用户手工标签来源必须为 user");
        }
        if (!CONFIRMED_STATUS.equals(documentTag.status())) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "用户手工标签保存后必须直接为 confirmed");
        }
        if (documentTag.confidence() != null
                || !isBlank(documentTag.extractionRunId())
                || !isBlank(documentTag.promptVersion())
                || !isBlank(documentTag.schemaVersion())) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "用户手工标签不能携带模型置信度、运行或版本字段");
        }
    }

    /**
     * 校验文档标签通用标识和空间字段。
     *
     * @param documentTag 文档标签关系
     */
    private void requireDocumentTag(DocumentTag documentTag) {
        if (documentTag == null
                || isBlank(documentTag.id())
                || isBlank(documentTag.spaceId())
                || isBlank(documentTag.sourceDocumentId())
                || isBlank(documentTag.tagId())
                || isBlank(documentTag.contentHash())) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "文档标签标识、空间、来源资料、标签和内容指纹不能为空");
        }
    }

    /**
     * 校验知识空间、来源资料归属和内容指纹快照。
     *
     * @param documentTag 文档标签关系
     * @return 当前有效来源资料
     */
    private SourceDocument requireSourceDocument(DocumentTag documentTag) {
        // 查询空间内有效来源资料，阻断跨空间引用
        SourceDocument sourceDocument = requireSourceDocument(
                documentTag.spaceId(),
                documentTag.sourceDocumentId()
        );
        if (!sourceDocument.contentHash().equals(documentTag.contentHash())) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "文档标签内容指纹与当前来源资料不一致");
        }
        return sourceDocument;
    }

    /**
     * 查询当前知识空间内的有效来源资料。
     *
     * @param spaceId 知识空间标识
     * @param sourceDocumentId 来源资料标识
     * @return 当前有效来源资料
     */
    private SourceDocument requireSourceDocument(
            String spaceId,
            String sourceDocumentId
    ) {
        // 校验知识空间有效，避免已删除空间继续写入标签
        knowledgeSpaceRepository.findActiveById(spaceId)
                .orElseThrow(() -> new TipsException(ErrorCode.NOT_FOUND, "知识空间不存在"));

        // 查询当前空间内的有效来源资料
        return sourceDocumentRepository.findById(spaceId, sourceDocumentId)
                .orElseThrow(() -> new TipsException(ErrorCode.NOT_FOUND, "来源资料不存在"));
    }

    /**
     * 规范化并复用空间内标签定义。
     *
     * @param tag 待保存标签定义
     * @param documentTag 当前文档标签关系
     * @return 已存在或新保存的有效标签定义
     */
    private KnowledgeTag resolveTag(
            KnowledgeTag tag,
            DocumentTag documentTag
    ) {
        if (tag == null
                || isBlank(tag.id())
                || isBlank(tag.spaceId())
                || isBlank(tag.name())) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "标签标识、空间和名称不能为空");
        }
        if (!tag.spaceId().equals(documentTag.spaceId()) || !tag.id().equals(documentTag.tagId())) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "标签定义与文档标签关系不一致");
        }
        if (!isBlank(tag.status()) && !ACTIVE_STATUS.equals(tag.status())) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "新建或复用的标签定义必须为 active");
        }

        // 合并首尾和连续空格，保留面向用户的稳定展示名称
        String normalizedName = WHITESPACE_PATTERN.matcher(tag.name().strip()).replaceAll(" ");
        int tagLength = normalizedName.codePointCount(0, normalizedName.length());
        if (tagLength < MIN_TAG_LENGTH || tagLength > MAX_TAG_LENGTH) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "标签名称长度必须在 2 到 24 个字符之间");
        }

        // 仅折叠大小写和空格差异，不自动合并语义相近标签
        String normalizedKey = normalizedName.toLowerCase(Locale.ROOT);

        // 优先复用当前空间内已存在的规范化标签定义
        KnowledgeTag existingTag = documentTagRepository.findTagByNormalizedKey(
                documentTag.spaceId(),
                normalizedKey
        ).orElse(null);
        if (existingTag != null) {
            return existingTag;
        }

        Instant createdAt = tag.createdAt() == null ? Instant.now() : tag.createdAt();
        Instant updatedAt = tag.updatedAt() == null ? createdAt : tag.updatedAt();
        KnowledgeTag preparedTag = new KnowledgeTag(
                tag.id().strip(),
                tag.spaceId().strip(),
                normalizedName,
                normalizedKey,
                ACTIVE_STATUS,
                createdAt,
                updatedAt
        );

        // 保存新的空间内规范化标签定义
        documentTagRepository.saveTag(preparedTag);
        return preparedTag;
    }

    /**
     * 规范化文档标签关系并生成冻结的稳定幂等键。
     *
     * @param documentTag 待保存文档标签关系
     * @param tag 已保存或复用的标签定义
     * @param sourceDocument 当前来源资料事实源
     * @return 已补齐标签标识、版本和稳定键的文档标签关系
     */
    private DocumentTag prepareDocumentTag(
            DocumentTag documentTag,
            KnowledgeTag tag,
            SourceDocument sourceDocument
    ) {
        // 按阶段 2 冻结签名计算文档标签稳定幂等键
        String documentTagKey = buildDocumentTagKey(
                documentTag.spaceId(),
                documentTag.sourceDocumentId(),
                documentTag.contentHash(),
                tag.normalizedKey(),
                documentTag.promptVersion(),
                documentTag.schemaVersion()
        );
        if (!isBlank(documentTag.documentTagKey())
                && !documentTagKey.equals(documentTag.documentTagKey())) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "文档标签幂等键与服务端计算结果不一致");
        }

        Instant createdAt = documentTag.createdAt() == null ? Instant.now() : documentTag.createdAt();
        Instant updatedAt = documentTag.updatedAt() == null ? createdAt : documentTag.updatedAt();
        return new DocumentTag(
                documentTag.id().strip(),
                documentTag.spaceId().strip(),
                sourceDocument.id(),
                tag.id(),
                documentTag.sourceType(),
                documentTag.status(),
                documentTag.confidence(),
                normalizeNullable(documentTag.extractionRunId()),
                sourceDocument.contentHash(),
                normalizeNullable(documentTag.promptVersion()),
                normalizeNullable(documentTag.schemaVersion()),
                documentTagKey,
                createdAt,
                updatedAt
        );
    }

    /**
     * 校验并补齐 AI 标签证据的逐字位置。
     *
     * @param documentTag 已规范化的文档标签关系
     * @param sourceDocument 当前来源资料事实源
     * @param evidences 待校验的模型证据
     * @return 可原子写入的证据列表
     */
    private List<DocumentTagEvidence> prepareEvidences(
            DocumentTag documentTag,
            SourceDocument sourceDocument,
            List<DocumentTagEvidence> evidences
    ) {
        Set<String> evidenceIds = new HashSet<>();

        // 逐条验证证据标识、文档归属、quote 和偏移
        return evidences.stream()
                .map(evidence -> prepareEvidence(documentTag, sourceDocument, evidence, evidenceIds))
                .toList();
    }

    /**
     * 校验单条证据并将未知偏移补齐为真实原文位置。
     *
     * @param documentTag 已规范化的文档标签关系
     * @param sourceDocument 当前来源资料事实源
     * @param evidence 待校验证据
     * @param evidenceIds 当前候选内已使用证据标识
     * @return 已通过逐字反查的证据
     */
    private DocumentTagEvidence prepareEvidence(
            DocumentTag documentTag,
            SourceDocument sourceDocument,
            DocumentTagEvidence evidence,
            Set<String> evidenceIds
    ) {
        if (evidence == null
                || isBlank(evidence.id())
                || isBlank(evidence.spaceId())
                || isBlank(evidence.documentTagId())
                || isBlank(evidence.sourceDocumentId())
                || isBlank(evidence.chunkId())
                || evidence.sectionPath() == null
                || isBlank(evidence.quote())) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "标签证据标识、归属、分片和原文不能为空");
        }
        if (!evidenceIds.add(evidence.id())) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "同一候选标签内的证据标识不能重复");
        }
        if (!documentTag.spaceId().equals(evidence.spaceId())
                || !documentTag.id().equals(evidence.documentTagId())
                || !documentTag.sourceDocumentId().equals(evidence.sourceDocumentId())) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "标签证据必须属于当前文档标签和来源资料");
        }

        String content = sourceDocument.contentText();
        int resolvedStart = evidence.startOffset() == null
                ? content.indexOf(evidence.quote())
                : evidence.startOffset();
        int resolvedEnd = evidence.endOffset() == null
                ? resolvedStart + evidence.quote().length()
                : evidence.endOffset();
        if (resolvedStart < 0
                || resolvedEnd < resolvedStart
                || resolvedEnd > content.length()
                || !content.substring(resolvedStart, resolvedEnd).equals(evidence.quote())) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "标签证据无法在当前来源资料中逐字反查");
        }

        return new DocumentTagEvidence(
                evidence.id().strip(),
                evidence.spaceId().strip(),
                documentTag.id(),
                sourceDocument.id(),
                evidence.chunkId().strip(),
                evidence.sectionPath().strip(),
                evidence.quote(),
                resolvedStart,
                resolvedEnd,
                evidence.createdAt() == null ? Instant.now() : evidence.createdAt()
        );
    }

    /**
     * 计算标签关系的 SHA-256 稳定幂等键。
     *
     * @param spaceId 知识空间标识
     * @param sourceDocumentId 来源资料标识
     * @param contentHash 来源资料内容指纹
     * @param normalizedTagKey 标签规范化键
     * @param promptVersion Prompt 版本；手工标签为空
     * @param schemaVersion Schema 版本；手工标签为空
     * @return 十六进制稳定幂等键
     */
    private String buildDocumentTagKey(
            String spaceId,
            String sourceDocumentId,
            String contentHash,
            String normalizedTagKey,
            String promptVersion,
            String schemaVersion
    ) {
        String signature = String.join(
                KEY_SEPARATOR,
                spaceId,
                sourceDocumentId,
                contentHash,
                normalizedTagKey,
                normalizeOptional(promptVersion),
                normalizeOptional(schemaVersion)
        );
        try {
            // 使用 JDK SHA-256 生成不暴露原文的稳定键
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            // 将稳定签名字节散列为小写十六进制文本
            return HexFormat.of().formatHex(digest.digest(signature.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", exception);
        }
    }

    /**
     * 将可选文本规范化为空字符串或去除首尾空格后的值。
     *
     * @param value 可选文本
     * @return 幂等签名可安全使用的文本
     */
    private String normalizeOptional(String value) {
        return value == null ? "" : value.strip();
    }

    /**
     * 将持久化可选字段规范化为 null 或去除首尾空格后的值。
     *
     * @param value 可选持久化文本
     * @return 空白时返回 null，否则返回规范化文本
     */
    private String normalizeNullable(String value) {
        return isBlank(value) ? null : value.strip();
    }

    /**
     * 判断文本是否为空或只包含空白字符。
     *
     * @param value 待判断文本
     * @return 为空或空白时返回 true
     */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

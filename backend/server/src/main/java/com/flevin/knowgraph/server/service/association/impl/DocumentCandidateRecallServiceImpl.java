package com.flevin.knowgraph.server.service.association.impl;

import com.flevin.knowgraph.common.enums.ErrorCode;
import com.flevin.knowgraph.common.exception.TipsException;
import com.flevin.knowgraph.server.model.ai.DocumentExtractionOverview;
import com.flevin.knowgraph.server.model.ai.rag.DocumentSection;
import com.flevin.knowgraph.server.model.association.DocumentCandidate;
import com.flevin.knowgraph.server.model.association.DocumentCandidateRecall;
import com.flevin.knowgraph.server.model.document.SourceDocument;
import com.flevin.knowgraph.server.repository.ai.AiExtractionRunRepository;
import com.flevin.knowgraph.server.repository.document.SourceDocumentRepository;
import com.flevin.knowgraph.server.repository.space.KnowledgeSpaceRepository;
import com.flevin.knowgraph.server.service.ai.rag.PrdMarkdownSectionParser;
import com.flevin.knowgraph.server.service.association.DocumentCandidateRecallService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 文档关联第一版确定性候选召回实现。
 *
 * <p>当前只使用文件名、确定性标题、章节标题、资料摘要和正文关键词，
 * 不使用标签、Embedding 或模型生成结果。规则分数只用于候选排序，
 * 不能替代后续关系判断、证据校验和人工审核。</p>
 */
@Service
@RequiredArgsConstructor
public class DocumentCandidateRecallServiceImpl implements DocumentCandidateRecallService {

    public static final String POLICY_VERSION = "document-candidate-recall-v1";

    private static final int DEFAULT_TOP_K = 8;
    private static final int MAX_TOP_K = 8;
    private static final int MIN_MEANINGFUL_TERM_LENGTH = 4;
    private static final Pattern TERM_PATTERN = Pattern.compile(
            "[\\p{IsHan}]{4,}|[A-Za-z0-9][A-Za-z0-9._-]{1,}"
    );
    private static final Set<String> GENERIC_TERMS = Set.of(
            "活动",
            "年会",
            "会议",
            "筹备",
            "方案",
            "计划",
            "预算",
            "场地",
            "供应商",
            "行政部",
            "人力资源部",
            "品牌组",
            "财务部",
            "草案",
            "报告",
            "审核",
            "意见",
            "执行",
            "手册",
            "通知",
            "模板",
            "内容",
            "项目",
            "主题",
            "日期",
            "人数",
            "安排",
            "结论",
            "会议依据",
            "会议纪要",
            "活动方案",
            "预算草案",
            "场地预算",
            "宣传物料",
            "总预算",
            "场地费用",
            "预算分类",
            "草案适用",
            "预算上限",
            "场地与预算",
            "活动目标",
            "执行情况",
            "后续行动",
            "物料清单",
            "年会筹备",
            "活动执行",
            "场地与安全",
            "预算与临时采购",
            "异常与备选流程",
            "会后收尾",
            "现场运营",
            "行政通知",
            "培训场地",
            "供应商报价",
            "财务审核"
    );
    private static final List<String> TITLE_SUFFIXES = List.of(
            "纪要",
            "报告",
            "草案",
            "意见",
            "模板",
            "通知",
            "计划",
            "手册"
    );
    private static final Set<String> GENERIC_FRAGMENTS = Set.of(
            "本草案",
            "适用于",
            "本计划",
            "本文件",
            "请填写",
            "不涉及",
            "本次会议",
            "不代表",
            "尚未",
            "继续按",
            "不能作为",
            "负责"
    );
    private static final Set<String> NEGATION_MARKERS = Set.of(
            "无关",
            "不涉及",
            "不对应",
            "不属于",
            "不是",
            "并非",
            "无关联"
    );

    private final KnowledgeSpaceRepository knowledgeSpaceRepository;
    private final SourceDocumentRepository sourceDocumentRepository;
    private final AiExtractionRunRepository aiExtractionRunRepository;
    private final PrdMarkdownSectionParser sectionParser;

    /**
     * 按冻结的 TopK=8 规则召回当前文档的关联候选。
     *
     * @param spaceId 知识空间标识
     * @param sourceDocumentId 当前作为召回主体的来源资料标识
     * @return 无 Embedding 候选召回结果
     */
    @Override
    @Transactional(readOnly = true)
    public DocumentCandidateRecall recall(
            String spaceId,
            String sourceDocumentId
    ) {
        // 使用冻结的候选上限，避免默认调用悄然改变评估基线
        return recall(spaceId, sourceDocumentId, DEFAULT_TOP_K);
    }

    /**
     * 按冻结策略召回当前文档的关联候选，并允许受控缩小 TopK。
     *
     * @param spaceId 知识空间标识
     * @param sourceDocumentId 当前作为召回主体的来源资料标识
     * @param topK 候选数量上限，取值范围为 1 到 8
     * @return 无 Embedding 候选召回结果
     */
    @Override
    @Transactional(readOnly = true)
    public DocumentCandidateRecall recall(
            String spaceId,
            String sourceDocumentId,
            int topK
    ) {
        // 校验候选数量，保证固定评估的 TopK 不被调用方扩大
        validateTopK(topK);

        // 校验知识空间仍然有效，保持候选召回的空间隔离
        requireActiveSpace(spaceId);

        // 查询当前有效主体文档，避免对已删除资料继续生成候选
        SourceDocument sourceDocument = sourceDocumentRepository.findById(spaceId, sourceDocumentId)
                .orElseThrow(() -> new TipsException(ErrorCode.NOT_FOUND, "来源资料不存在"));

        // 一次读取当前空间全部有效来源资料，后续在内存中排除主体并完成规则融合
        List<SourceDocument> activeDocuments = sourceDocumentRepository.findAll(spaceId);
        List<SourceDocument> candidateDocuments = activeDocuments.stream()
                .filter(document -> !document.id().equals(sourceDocument.id()))
                .toList();

        // 批量读取全部有效资料的最近成功自然摘要，避免逐文档查询形成 N+1
        List<String> documentIds = activeDocuments.stream().map(SourceDocument::id).toList();
        Map<String, DocumentExtractionOverview> extractionOverviews = aiExtractionRunRepository
                .findLatestByDocuments(spaceId, documentIds)
                .stream()
                .collect(Collectors.toMap(DocumentExtractionOverview::documentId, Function.identity()));

        // 分别解析主体和候选资料的标题、章节、最新摘要及关键词元数据
        DocumentProfile sourceProfile = buildProfile(
                sourceDocument,
                resolveSummary(sourceDocument, extractionOverviews)
        );

        // 对每份候选只做一次确定性评分，避免循环中重复解析同一原文
        List<ScoredCandidate> scoredCandidates = candidateDocuments.stream()
                .map(candidate -> scoreCandidate(
                        sourceProfile,
                        candidate,
                        resolveSummary(candidate, extractionOverviews)
                ))
                .flatMap(java.util.Optional::stream)
                .sorted(ScoredCandidate.ORDER)
                .limit(topK)
                .toList();

        // 将稳定排序结果转换为供后续关系判断使用的候选领域模型
        List<DocumentCandidate> candidates = new ArrayList<>(scoredCandidates.size());
        for (int index = 0; index < scoredCandidates.size(); index++) {
            // 使用 1-based 排名，便于运行详情和评估报告直接展示
            candidates.add(toCandidate(scoredCandidates.get(index), index + 1));
        }

        return new DocumentCandidateRecall(
                spaceId,
                sourceDocument.id(),
                sourceDocument.contentHash(),
                POLICY_VERSION,
                topK,
                candidates
        );
    }

    /**
     * 校验候选数量范围。
     *
     * @param topK 候选数量上限
     */
    private void validateTopK(int topK) {
        if (topK < 1 || topK > MAX_TOP_K) {
            throw new TipsException(ErrorCode.PARAM_ERROR, "文档候选召回 TopK 必须在 1 到 8 之间");
        }
    }

    /**
     * 校验知识空间有效状态。
     *
     * @param spaceId 知识空间标识
     */
    private void requireActiveSpace(String spaceId) {
        // 通过有效空间查询阻断跨空间或已删除空间的候选召回
        knowledgeSpaceRepository.findActiveById(spaceId)
                .orElseThrow(() -> new TipsException(ErrorCode.NOT_FOUND, "知识空间不存在"));
    }

    /**
     * 构造来源资料的确定性检索元数据。
     *
     * @param document 来源资料
     * @param summary 最近一次成功自然摘要；不存在时使用导入预览
     * @return 标题、章节、摘要和正文关键词画像
     */
    private DocumentProfile buildProfile(
            SourceDocument document,
            String summary
    ) {
        // 复用章节感知解析器，保持候选召回与后续分片的标题识别规则一致
        List<DocumentSection> sections = sectionParser.parse(document.contentText());

        // 优先使用第一个 Markdown 一级或更深标题，没有标题时回退文件名
        String title = sections.stream()
                .filter(section -> section.level() > 0)
                .map(DocumentSection::title)
                .findFirst()
                .orElseGet(() -> removeExtension(document.name()));

        // 保留章节标题集合，避免把全文正文中的通用词误当成标题信号
        Set<String> sectionTerms = sections.stream()
                .filter(section -> section.level() > 0)
                .map(DocumentSection::title)
                .map(this::extractTerms)
                .flatMap(Set::stream)
                .collect(Collectors.toUnmodifiableSet());

        // 分别提取标题、摘要和全文关键词，后续按固定通道计算分数
        Set<String> titleTerms = extractTerms(title);
        Set<String> summaryTerms = extractTerms(summary);
        Set<String> bodyTerms = extractTerms(document.contentText());

        return new DocumentProfile(
                document,
                summary,
                title,
                normalizeForComparison(removeExtension(document.name())),
                normalizeForComparison(title),
                titleTerms,
                sectionTerms,
                summaryTerms,
                bodyTerms
        );
    }

    /**
     * 对候选资料执行显式引用、标题、章节、摘要和关键词通道融合。
     *
     * @param sourceProfile 当前主体文档画像
     * @param candidate 候选来源资料
     * @param candidateSummary 候选资料最近一次成功自然摘要或导入预览
     * @return 命中至少一个可解释通道时返回评分结果
     */
    private java.util.Optional<ScoredCandidate> scoreCandidate(
            DocumentProfile sourceProfile,
            SourceDocument candidate,
            String candidateSummary
    ) {
        // 解析候选资料画像，保证每份候选只读取一次章节和关键词
        DocumentProfile candidateProfile = buildProfile(candidate, candidateSummary);

        // 显式检查任一文档正文是否点名另一份资料的文件名或确定性标题
        boolean explicitReference = isExplicitReference(sourceProfile, candidateProfile);

        // 计算标题、章节标题、摘要和全文关键词的交集，保留解释性命中词
        Set<String> titleMatches = intersection(sourceProfile.titleTerms(), candidateProfile.titleTerms());
        Set<String> sectionMatches = intersection(sourceProfile.sectionTerms(), candidateProfile.sectionTerms());
        Set<String> summaryMatches = intersection(sourceProfile.summaryTerms(), candidateProfile.summaryTerms()).stream()
                .filter(term -> hasPositiveOccurrence(sourceProfile.summary(), term))
                .filter(term -> hasPositiveOccurrence(candidateProfile.summary(), term))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> keywordMatches = intersection(sourceProfile.bodyTerms(), candidateProfile.bodyTerms()).stream()
                .filter(this::isMeaningfulTerm)
                .filter(term -> hasPositiveOccurrence(sourceProfile.document().contentText(), term))
                .filter(term -> hasPositiveOccurrence(candidateProfile.document().contentText(), term))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // 只有明确引用、标题/章节/摘要命中或高信息量关键词命中才进入候选集合
        if (!explicitReference
                && titleMatches.isEmpty()
                && sectionMatches.isEmpty()
                && summaryMatches.isEmpty()
                && keywordMatches.isEmpty()) {
            return java.util.Optional.empty();
        }

        // 按 PRD 固定通道优先级计算规则分数，分数只用于排序不代表关系置信度
        int score = 0;
        if (explicitReference) {
            score += 100;
        }
        score += weightedOverlap(titleMatches, 70);
        score += weightedOverlap(sectionMatches, 45);
        score += weightedOverlap(summaryMatches, 30);
        score += weightedOverlap(keywordMatches, 12);

        // 记录稳定通道顺序，供后续模型上下文和人工排查解释召回来源
        List<String> matchedChannels = new ArrayList<>();
        if (explicitReference) {
            matchedChannels.add("explicit_reference");
        }
        if (!titleMatches.isEmpty()) {
            matchedChannels.add("title_match");
        }
        if (!sectionMatches.isEmpty()) {
            matchedChannels.add("section_title_match");
        }
        if (!summaryMatches.isEmpty()) {
            matchedChannels.add("summary_match");
        }
        if (!keywordMatches.isEmpty()) {
            matchedChannels.add("keyword_match");
        }

        // 合并通道命中词并按长度、字典序稳定排序，避免 Fake 评估出现随机结果
        Set<String> matchedTerms = new LinkedHashSet<>();
        matchedTerms.addAll(titleMatches);
        matchedTerms.addAll(sectionMatches);
        matchedTerms.addAll(summaryMatches);
        matchedTerms.addAll(keywordMatches);

        List<String> orderedTerms = matchedTerms.stream()
                .sorted(Comparator.comparingInt(String::length).reversed().thenComparing(String::compareTo))
                .limit(12)
                .toList();

        return java.util.Optional.of(new ScoredCandidate(
                candidateProfile,
                matchedChannels,
                orderedTerms,
                score
        ));
    }

    /**
     * 判断两份资料是否存在文件名、标题或标题核心片段的显式互引。
     *
     * @param sourceProfile 主体文档画像
     * @param candidateProfile 候选文档画像
     * @return 任一正文明确出现对方标识时返回 true
     */
    private boolean isExplicitReference(
            DocumentProfile sourceProfile,
            DocumentProfile candidateProfile
    ) {
        // 规范化正文后再比较，忽略 Markdown 标记、空格和中文标点差异
        String sourceText = normalizeForComparison(sourceProfile.document().contentText());
        String candidateText = normalizeForComparison(candidateProfile.document().contentText());

        // 同时检查完整标题、文件名和去通用后缀标题，覆盖版本和“会议纪要/报告”等写法
        List<String> sourceIdentities = identities(sourceProfile);
        List<String> candidateIdentities = identities(candidateProfile);

        // 当前文档点名候选文档，或候选文档反向点名当前文档，均视为显式召回
        return candidateIdentities.stream().anyMatch(identity -> sourceText.contains(identity))
                || sourceIdentities.stream().anyMatch(identity -> candidateText.contains(identity));
    }

    /**
     * 生成用于显式引用匹配的稳定标识片段。
     *
     * @param profile 来源资料画像
     * @return 去重后的标题和文件名标识
     */
    private List<String> identities(DocumentProfile profile) {
        Set<String> identities = new LinkedHashSet<>();
        addIdentity(identities, profile.normalizedTitle());
        addIdentity(identities, profile.normalizedName());
        addIdentity(identities, removeTitleSuffix(profile.normalizedTitle()));
        addIdentity(identities, removeTitleSuffix(profile.normalizedName()));
        return identities.stream().filter(identity -> identity.length() >= 6).toList();
    }

    /**
     * 将候选画像转换为对外领域模型。
     *
     * @param scoredCandidate 评分候选
     * @param rank 稳定排名
     * @return 文档候选领域模型
     */
    private DocumentCandidate toCandidate(
            ScoredCandidate scoredCandidate,
            int rank
    ) {
        SourceDocument document = scoredCandidate.profile().document();
        return new DocumentCandidate(
                document.id(),
                document.name(),
                document.kind(),
                document.documentType(),
                document.contentHash(),
                scoredCandidate.profile().summary(),
                scoredCandidate.profile().title(),
                scoredCandidate.matchedChannels(),
                scoredCandidate.matchedTerms(),
                scoredCandidate.score(),
                rank
        );
    }

    /**
     * 计算两个关键词集合的交集。
     *
     * @param left 左侧关键词集合
     * @param right 右侧关键词集合
     * @return 保持左侧插入顺序的交集
     */
    private Set<String> intersection(Set<String> left, Set<String> right) {
        return left.stream()
                .filter(right::contains)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 计算一组命中词的有界加权分数。
     *
     * @param terms 命中关键词
     * @param perTerm 单词基础分
     * @return 不超过 5 个关键词贡献的排序分数
     */
    private int weightedOverlap(Set<String> terms, int perTerm) {
        return terms.stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .limit(5)
                .mapToInt(term -> perTerm + Math.min(20, term.length()))
                .sum();
    }

    /**
     * 提取可用于确定性内容召回的中文短语、英文词和编号。
     *
     * @param text 原始文本
     * @return 去除通用词后的稳定关键词集合
     */
    private Set<String> extractTerms(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }

        // 将标点和空白变成分隔符，避免不相关句子被拼成一个超长关键词
        String tokenText = text.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsHan}A-Za-z0-9]+", " ");
        Matcher matcher = TERM_PATTERN.matcher(tokenText);
        Set<String> terms = new LinkedHashSet<>();
        while (matcher.find()) {
            // 对中文连续短语生成 4～8 字窗口，兼顾项目名、版本号和关键业务短语
            addTermWindows(matcher.group(), terms);
        }
        return Set.copyOf(terms);
    }

    /**
     * 将单个词元加入关键词集合。
     *
     * @param token 词元
     * @param terms 目标关键词集合
     */
    private void addTermWindows(String token, Set<String> terms) {
        if (token.chars().allMatch(Character::isLetterOrDigit)
                && token.chars().anyMatch(character -> character < 128)) {
            // 英文、数字和版本号保留完整词元；纯数字不会单独触发候选资格
            if (token.length() >= 3 && !token.chars().allMatch(Character::isDigit)) {
                terms.add(token);
            }
            return;
        }

        int maxWindow = Math.min(8, token.length());
        for (int window = MIN_MEANINGFUL_TERM_LENGTH; window <= maxWindow; window++) {
            for (int start = 0; start + window <= token.length(); start++) {
                // 将连续中文短语拆成有限窗口，避免把整段正文当作单个关键词
                String term = token.substring(start, start + window);
                if (!isGenericTerm(term)) {
                    terms.add(term);
                }
            }
        }
    }

    /**
     * 判断关键词是否足以作为正文召回信号。
     *
     * @param term 待判断关键词
     * @return 不是纯数字且不属于通用词时返回 true
     */
    private boolean isMeaningfulTerm(String term) {
        return term.length() >= MIN_MEANINGFUL_TERM_LENGTH
                && !isGenericTerm(term)
                && !term.chars().allMatch(Character::isDigit);
    }

    /**
     * 判断关键词是否至少在一处非否定上下文中出现。
     *
     * @param text 待检查原文
     * @param term 关键词
     * @return 存在非“无关/不涉及”等否定上下文时返回 true
     */
    private boolean hasPositiveOccurrence(String text, String term) {
        String normalizedText = normalizeForComparison(text);
        String normalizedTerm = normalizeForComparison(term);
        if (normalizedText.isBlank() || normalizedTerm.isBlank()) {
            return false;
        }

        int searchStart = 0;
        while (searchStart < normalizedText.length()) {
            // 查找关键词的每一次出现，避免只因一处否定表述就丢失其他正向证据
            int occurrence = normalizedText.indexOf(normalizedTerm, searchStart);
            if (occurrence < 0) {
                return false;
            }

            int contextStart = Math.max(0, occurrence - 8);
            int contextEnd = Math.min(
                    normalizedText.length(),
                    occurrence + normalizedTerm.length() + 8
            );
            String context = normalizedText.substring(contextStart, contextEnd);
            if (NEGATION_MARKERS.stream().noneMatch(context::contains)) {
                return true;
            }
            searchStart = occurrence + normalizedTerm.length();
        }
        return false;
    }

    /**
     * 判断关键词是否属于模板化或文档类型噪声。
     *
     * @param term 待判断关键词
     * @return 通用词、文档类型后缀或固定模板短语返回 true
     */
    private boolean isGenericTerm(String term) {
        return GENERIC_TERMS.contains(term)
                || TITLE_SUFFIXES.stream().anyMatch(term::endsWith)
                || GENERIC_FRAGMENTS.stream().anyMatch(term::contains);
    }

    /**
     * 规范化比较文本，统一大小写并移除空白、标点和扩展名差异。
     *
     * @param text 原始文本
     * @return 只保留中文、英文和数字的比较文本
     */
    private String normalizeForComparison(String text) {
        return text == null
                ? ""
                : text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{IsHan}A-Za-z0-9]+", "");
    }

    /**
     * 去除文件扩展名，保留用户可读的文件名主体。
     *
     * @param fileName 原始文件名
     * @return 去除最后扩展名后的文件名
     */
    private String removeExtension(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }
        int extensionIndex = fileName.lastIndexOf('.');
        return extensionIndex > 0 ? fileName.substring(0, extensionIndex) : fileName;
    }

    /**
     * 去除标题末尾的通用文档类型后缀，用于识别“会议纪要/比选报告”等互引写法。
     *
     * @param normalizedTitle 已规范化标题
     * @return 去除最多一个通用后缀后的标题
     */
    private String removeTitleSuffix(String normalizedTitle) {
        String value = normalizedTitle;
        for (String suffix : TITLE_SUFFIXES) {
            if (value.endsWith(suffix) && value.length() - suffix.length() >= 6) {
                return value.substring(0, value.length() - suffix.length());
            }
        }
        return value;
    }

    /**
     * 将非空标识加入候选集合。
     *
     * @param identities 标识集合
     * @param identity 待加入标识
     */
    private void addIdentity(Set<String> identities, String identity) {
        if (identity != null && !identity.isBlank()) {
            identities.add(identity);
        }
    }

    /**
     * 选择候选召回使用的文档摘要。
     *
     * @param document 来源资料
     * @param extractionOverviews 当前空间最近抽取概览
     * @return 最近一次成功自然摘要；没有时回退导入预览
     */
    private String resolveSummary(
            SourceDocument document,
            Map<String, DocumentExtractionOverview> extractionOverviews
    ) {
        DocumentExtractionOverview overview = extractionOverviews.get(document.id());
        return overview == null || overview.latestCompletedSummary() == null
                || overview.latestCompletedSummary().isBlank()
                ? document.excerpt()
                : overview.latestCompletedSummary();
    }

    /**
     * 来源资料确定性检索画像。
     *
     * @param document 原始来源资料
     * @param summary 最近一次成功自然摘要或导入预览
     * @param title 确定性标题
     * @param normalizedName 规范化文件名
     * @param normalizedTitle 规范化标题
     * @param titleTerms 标题关键词
     * @param sectionTerms 章节标题关键词
     * @param summaryTerms 摘要关键词
     * @param bodyTerms 正文关键词
     */
    private record DocumentProfile(
            SourceDocument document,
            String summary,
            String title,
            String normalizedName,
            String normalizedTitle,
            Set<String> titleTerms,
            Set<String> sectionTerms,
            Set<String> summaryTerms,
            Set<String> bodyTerms
    ) {
    }

    /**
     * 已通过通道融合的候选评分中间模型。
     *
     * @param profile 候选资料画像
     * @param matchedChannels 命中通道
     * @param matchedTerms 命中关键词
     * @param score 规则排序分数
     */
    private record ScoredCandidate(
            DocumentProfile profile,
            List<String> matchedChannels,
            List<String> matchedTerms,
            int score
    ) {

        private static final Comparator<ScoredCandidate> ORDER = Comparator
                .comparingInt(ScoredCandidate::primaryChannelPriority)
                .thenComparing(Comparator.comparingInt(ScoredCandidate::score).reversed())
                .thenComparing(candidate -> candidate.profile().document().id());

        private int primaryChannelPriority() {
            if (matchedChannels.contains("explicit_reference")) {
                return 0;
            }
            if (matchedChannels.contains("title_match")) {
                return 1;
            }
            if (matchedChannels.contains("section_title_match")) {
                return 2;
            }
            if (matchedChannels.contains("summary_match")) {
                return 3;
            }
            return 4;
        }
    }
}

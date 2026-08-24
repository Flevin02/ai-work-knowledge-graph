package com.flevin.knowgraph.server.tag;

import com.flevin.knowgraph.common.exception.TipsException;
import com.flevin.knowgraph.server.model.document.DocumentImportResponse;
import com.flevin.knowgraph.server.model.document.SourceDocument;
import com.flevin.knowgraph.server.model.space.CreateKnowledgeSpaceRequest;
import com.flevin.knowgraph.server.model.space.KnowledgeSpaceResponse;
import com.flevin.knowgraph.server.model.tag.DocumentTag;
import com.flevin.knowgraph.server.model.tag.DocumentTagEvidence;
import com.flevin.knowgraph.server.model.tag.KnowledgeTag;
import com.flevin.knowgraph.server.repository.document.SourceDocumentRepository;
import com.flevin.knowgraph.server.repository.tag.DocumentTagRepository;
import com.flevin.knowgraph.server.service.document.DocumentService;
import com.flevin.knowgraph.server.service.space.KnowledgeSpaceService;
import com.flevin.knowgraph.server.service.tag.DocumentTagPersistenceService;
import com.flevin.knowgraph.server.support.TestKnowledgeSpaceFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 可选标签阶段 2 持久化基础集成测试。
 */
@SpringBootTest(properties = {
        "app.database-path=target/test-data/document-tag-persistence.sqlite",
        "app.upload-dir=target/test-data/document-tag-persistence-uploads"
})
class DocumentTagPersistenceIntegrationTests {

    private static final String DEFAULT_SPACE_ID = TestKnowledgeSpaceFixtures.DEFAULT_SPACE_ID;
    private static final String PROMPT_VERSION = "document-tag-v1";
    private static final String SCHEMA_VERSION = "document-tag-v1";
    private static final Instant TEST_TIME = Instant.parse("2026-08-24T08:00:00Z");

    @Autowired
    private DocumentTagPersistenceService persistenceService;

    @Autowired
    private DocumentTagRepository documentTagRepository;

    @Autowired
    private SourceDocumentRepository sourceDocumentRepository;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private KnowledgeSpaceService knowledgeSpaceService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearTagData() {
        // 按外键依赖顺序清理标签证据、文档标签和标签字典
        jdbcTemplate.update("DELETE FROM document_tag_evidences");
        jdbcTemplate.update("DELETE FROM document_tags");
        jdbcTemplate.update("DELETE FROM tags");

        // 清理其他领域的来源资料依赖，保证固定测试数据库可以重复执行
        jdbcTemplate.update("DELETE FROM document_relation_reviews");
        jdbcTemplate.update("DELETE FROM document_relation_evidences");
        jdbcTemplate.update("DELETE FROM document_relations");
        jdbcTemplate.update("DELETE FROM document_association_runs");
        jdbcTemplate.update("DELETE FROM review_actions");
        jdbcTemplate.update("DELETE FROM evidences");
        jdbcTemplate.update("DELETE FROM graph_edges");
        jdbcTemplate.update("DELETE FROM graph_nodes");
        jdbcTemplate.update("DELETE FROM ai_extraction_runs");
        jdbcTemplate.update("DELETE FROM source_documents");
        jdbcTemplate.update("DELETE FROM import_batches");

        // 清理非默认空间并恢复固定测试空间
        jdbcTemplate.update("DELETE FROM knowledge_spaces WHERE id <> ?", DEFAULT_SPACE_ID);
        TestKnowledgeSpaceFixtures.ensureDefaultSpace(jdbcTemplate);
    }

    @Test
    void createsTagTablesAndPersistsAiSuggestionWithVerbatimEvidence() {
        // 查询当前 SQLite 表结构，确认标签持久化基础已经初始化
        Set<String> tableNames = Set.copyOf(jdbcTemplate.queryForList(
                "SELECT name FROM sqlite_master WHERE type = 'table'",
                String.class
        ));
        assertThat(tableNames).contains("tags", "document_tags", "document_tag_evidences");

        SourceDocument document = importDocument(
                DEFAULT_SPACE_ID,
                "年会筹备方案.md",
                "星桥科技年度年会筹备正式启动，场地方案将在本周确认。"
        );
        KnowledgeTag tag = newTag("tag-annual-meeting", "  年会   筹备  ");
        DocumentTag suggestion = newAiSuggestion(
                "document-tag-ai-1",
                tag.id(),
                document,
                PROMPT_VERSION
        );
        String quote = "年度年会筹备正式启动";

        // 原子保存 AI 候选标签及可逐字反查证据
        DocumentTag savedTag = persistenceService.saveAiSuggestion(
                tag,
                suggestion,
                List.of(newEvidence("tag-evidence-1", suggestion, document, quote))
        );

        assertThat(savedTag.sourceType()).isEqualTo("ai");
        assertThat(savedTag.status()).isEqualTo("suggested");
        assertThat(savedTag.documentTagKey()).hasSize(64);
        assertThat(documentTagRepository.findTagByNormalizedKey(DEFAULT_SPACE_ID, "年会 筹备"))
                .get()
                .extracting(KnowledgeTag::name, KnowledgeTag::status)
                .containsExactly("年会 筹备", "active");
        assertThat(persistenceService.listEvidence(DEFAULT_SPACE_ID, savedTag.id()))
                .singleElement()
                .satisfies(evidence -> {
                    assertThat(evidence.quote()).isEqualTo(quote);
                    assertThat(evidence.startOffset()).isEqualTo(document.contentText().indexOf(quote));
                    assertThat(evidence.endOffset()).isEqualTo(
                            document.contentText().indexOf(quote) + quote.length()
                    );
                });
    }

    @Test
    void reusesNormalizedTagAndSeparatesPromptVersionsInIdempotencyKey() {
        SourceDocument document = importDocument(
                DEFAULT_SPACE_ID,
                "Annual Plan.md",
                "Annual Plan records the confirmed annual meeting scope."
        );
        KnowledgeTag firstDefinition = newTag("tag-annual-plan", "  Annual   Plan ");
        DocumentTag firstSuggestion = newAiSuggestion(
                "document-tag-v1",
                firstDefinition.id(),
                document,
                PROMPT_VERSION
        );

        // 首次运行保存规范化标签和 v1 候选关系
        DocumentTag firstSaved = persistenceService.saveAiSuggestion(
                firstDefinition,
                firstSuggestion,
                List.of(newEvidence(
                        "tag-evidence-v1",
                        firstSuggestion,
                        document,
                        "Annual Plan"
                ))
        );

        KnowledgeTag duplicateDefinition = newTag("tag-annual-plan-duplicate", "annual plan");
        DocumentTag duplicateSuggestion = newAiSuggestion(
                "document-tag-v1-duplicate",
                duplicateDefinition.id(),
                document,
                PROMPT_VERSION
        );

        // 相同内容、规范化标签和版本重复运行时复用原候选与标签定义
        DocumentTag duplicateSaved = persistenceService.saveAiSuggestion(
                duplicateDefinition,
                duplicateSuggestion,
                List.of(newEvidence(
                        "tag-evidence-v1-duplicate",
                        duplicateSuggestion,
                        document,
                        "Annual Plan"
                ))
        );
        assertThat(duplicateSaved.id()).isEqualTo(firstSaved.id());
        assertThat(duplicateSaved.tagId()).isEqualTo(firstSaved.tagId());
        assertThat(persistenceService.listEvidence(DEFAULT_SPACE_ID, firstSaved.id())).hasSize(1);

        DocumentTag nextPromptSuggestion = newAiSuggestion(
                "document-tag-v2",
                duplicateDefinition.id(),
                document,
                "document-tag-v2"
        );

        // 仅改变 Prompt 版本时创建新候选，保留旧版本历史且继续复用标签字典
        DocumentTag nextPromptSaved = persistenceService.saveAiSuggestion(
                duplicateDefinition,
                nextPromptSuggestion,
                List.of(newEvidence(
                        "tag-evidence-v2",
                        nextPromptSuggestion,
                        document,
                        "Annual Plan"
                ))
        );
        assertThat(nextPromptSaved.id()).isEqualTo("document-tag-v2");
        assertThat(nextPromptSaved.tagId()).isEqualTo(firstSaved.tagId());
        assertThat(nextPromptSaved.documentTagKey()).isNotEqualTo(firstSaved.documentTagKey());
        assertThat(persistenceService.listDocumentTags(DEFAULT_SPACE_ID, document.id())).hasSize(2);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM tags", Integer.class)).isOne();
    }

    @Test
    void persistsUserTagAsConfirmedAndRejectsInvalidInitialSourceStates() {
        SourceDocument document = importDocument(
                DEFAULT_SPACE_ID,
                "年度预算.md",
                "年度预算由财务部门维护。"
        );
        KnowledgeTag userDefinition = newTag("tag-budget", "年度预算");
        DocumentTag userTag = new DocumentTag(
                "document-tag-user-1",
                DEFAULT_SPACE_ID,
                document.id(),
                userDefinition.id(),
                "user",
                "confirmed",
                null,
                null,
                document.contentHash(),
                null,
                null,
                null,
                TEST_TIME,
                TEST_TIME
        );

        // 保存用户手工标签，验证不经过 AI 建议态即可直接确认
        DocumentTag savedUserTag = persistenceService.saveUserTag(userDefinition, userTag);
        assertThat(savedUserTag.status()).isEqualTo("confirmed");
        assertThat(savedUserTag.confidence()).isNull();
        assertThat(savedUserTag.promptVersion()).isNull();
        assertThat(persistenceService.listEvidence(DEFAULT_SPACE_ID, savedUserTag.id())).isEmpty();

        DocumentTag invalidAiState = new DocumentTag(
                "document-tag-ai-confirmed",
                DEFAULT_SPACE_ID,
                document.id(),
                userDefinition.id(),
                "ai",
                "confirmed",
                0.9,
                "tag-run-invalid-ai",
                document.contentHash(),
                PROMPT_VERSION,
                SCHEMA_VERSION,
                null,
                TEST_TIME,
                TEST_TIME
        );

        // AI 标签不能在创建时绕过人工审核直接进入 confirmed
        assertThatThrownBy(() -> persistenceService.saveAiSuggestion(
                userDefinition,
                invalidAiState,
                List.of(newEvidence(
                        "tag-evidence-invalid-ai",
                        invalidAiState,
                        document,
                        "年度预算"
                ))
        ))
                .isInstanceOf(TipsException.class)
                .hasMessage("AI 候选标签初始状态必须为 suggested");

        DocumentTag invalidUserState = new DocumentTag(
                "document-tag-user-suggested",
                DEFAULT_SPACE_ID,
                document.id(),
                userDefinition.id(),
                "user",
                "suggested",
                null,
                null,
                document.contentHash(),
                null,
                null,
                null,
                TEST_TIME,
                TEST_TIME
        );

        // 用户手工标签不能伪装成待审核 AI 候选
        assertThatThrownBy(() -> persistenceService.saveUserTag(userDefinition, invalidUserState))
                .isInstanceOf(TipsException.class)
                .hasMessage("用户手工标签保存后必须直接为 confirmed");
    }

    @Test
    void rejectsCrossSpaceFingerprintAndInvalidEvidenceWithoutPartialWrites() {
        SourceDocument document = importDocument(
                DEFAULT_SPACE_ID,
                "证据校验.md",
                "标签证据必须来自当前文档中的真实原文。"
        );
        KnowledgeSpaceResponse otherSpace = knowledgeSpaceService.createSpace(
                new CreateKnowledgeSpaceRequest("标签隔离空间", "验证标签不能跨空间引用")
        );
        SourceDocument otherDocument = importDocument(
                otherSpace.id(),
                "其他空间.md",
                "其他空间的标签内容。"
        );

        KnowledgeTag crossSpaceDefinition = newTag("tag-cross-space", "跨空间标签");
        DocumentTag crossSpaceSuggestion = newAiSuggestion(
                "document-tag-cross-space",
                crossSpaceDefinition.id(),
                otherDocument,
                PROMPT_VERSION
        );
        crossSpaceSuggestion = copyWithSpace(crossSpaceSuggestion, DEFAULT_SPACE_ID);

        // 路径空间和来源资料空间不一致时必须在任何标签写入前拒绝
        DocumentTag finalCrossSpaceSuggestion = crossSpaceSuggestion;
        assertThatThrownBy(() -> persistenceService.saveAiSuggestion(
                crossSpaceDefinition,
                finalCrossSpaceSuggestion,
                List.of(newEvidence(
                        "tag-evidence-cross-space",
                        finalCrossSpaceSuggestion,
                        otherDocument,
                        "其他空间的标签内容"
                ))
        ))
                .isInstanceOf(TipsException.class)
                .hasMessage("来源资料不存在");

        KnowledgeTag invalidHashDefinition = newTag("tag-invalid-hash", "指纹校验");
        DocumentTag invalidHashSuggestion = newAiSuggestion(
                "document-tag-invalid-hash",
                invalidHashDefinition.id(),
                document,
                PROMPT_VERSION
        );
        invalidHashSuggestion = copyWithContentHash(invalidHashSuggestion, "invalid-content-hash");

        // 标签运行使用过期或伪造内容指纹时必须拒绝
        DocumentTag finalInvalidHashSuggestion = invalidHashSuggestion;
        assertThatThrownBy(() -> persistenceService.saveAiSuggestion(
                invalidHashDefinition,
                finalInvalidHashSuggestion,
                List.of(newEvidence(
                        "tag-evidence-invalid-hash",
                        finalInvalidHashSuggestion,
                        document,
                        "标签证据"
                ))
        ))
                .isInstanceOf(TipsException.class)
                .hasMessage("文档标签内容指纹与当前来源资料不一致");

        KnowledgeTag invalidEvidenceDefinition = newTag("tag-invalid-evidence", "无效证据");
        DocumentTag invalidEvidenceSuggestion = newAiSuggestion(
                "document-tag-invalid-evidence",
                invalidEvidenceDefinition.id(),
                document,
                PROMPT_VERSION
        );

        // quote 不存在时整个事务回滚，不能留下孤立标签定义或无证据候选
        assertThatThrownBy(() -> persistenceService.saveAiSuggestion(
                invalidEvidenceDefinition,
                invalidEvidenceSuggestion,
                List.of(newEvidence(
                        "tag-evidence-invalid-quote",
                        invalidEvidenceSuggestion,
                        document,
                        "这段原文并不存在"
                ))
        ))
                .isInstanceOf(TipsException.class)
                .hasMessage("标签证据无法在当前来源资料中逐字反查");
        assertThat(documentTagRepository.findTagByNormalizedKey(DEFAULT_SPACE_ID, "无效证据"))
                .isEmpty();
        assertThat(persistenceService.listDocumentTags(DEFAULT_SPACE_ID, document.id())).isEmpty();
    }

    /**
     * 通过现有来源资料导入链路创建真实文档和内容指纹。
     *
     * @param spaceId 知识空间标识
     * @param fileName 虚构文件名
     * @param content 虚构文档内容
     * @return 已持久化来源资料
     */
    private SourceDocument importDocument(
            String spaceId,
            String fileName,
            String content
    ) {
        MockMultipartFile file = new MockMultipartFile(
                "files",
                fileName,
                "text/markdown",
                content.getBytes(StandardCharsets.UTF_8)
        );

        // 使用现有导入 Service 保存来源文件、解析原文和内容指纹
        DocumentImportResponse response = documentService.importDocuments(spaceId, List.of(file));
        String documentId = response.results().getFirst().document().id();

        // 读取完整来源资料，供标签证据逐字反查
        return sourceDocumentRepository.findById(spaceId, documentId).orElseThrow();
    }

    /**
     * 创建固定标签定义输入。
     *
     * @param tagId 标签标识
     * @param name 原始展示名称
     * @return 待规范化标签定义
     */
    private KnowledgeTag newTag(
            String tagId,
            String name
    ) {
        return new KnowledgeTag(
                tagId,
                DEFAULT_SPACE_ID,
                name,
                null,
                "active",
                TEST_TIME,
                TEST_TIME
        );
    }

    /**
     * 创建固定 AI 候选文档标签输入。
     *
     * @param documentTagId 文档标签关系标识
     * @param tagId 输入标签标识
     * @param document 来源资料
     * @param promptVersion Prompt 版本
     * @return 待保存 AI 候选标签
     */
    private DocumentTag newAiSuggestion(
            String documentTagId,
            String tagId,
            SourceDocument document,
            String promptVersion
    ) {
        return new DocumentTag(
                documentTagId,
                DEFAULT_SPACE_ID,
                document.id(),
                tagId,
                "ai",
                "suggested",
                0.91,
                "tag-run-" + documentTagId,
                document.contentHash(),
                promptVersion,
                SCHEMA_VERSION,
                null,
                TEST_TIME,
                TEST_TIME
        );
    }

    /**
     * 创建由 Service 补齐原文偏移的固定标签证据。
     *
     * @param evidenceId 证据标识
     * @param documentTag 文档标签关系
     * @param document 来源资料
     * @param quote 可逐字反查原文
     * @return 待校验标签证据
     */
    private DocumentTagEvidence newEvidence(
            String evidenceId,
            DocumentTag documentTag,
            SourceDocument document,
            String quote
    ) {
        return new DocumentTagEvidence(
                evidenceId,
                documentTag.spaceId(),
                documentTag.id(),
                document.id(),
                "section-1-chunk-1",
                "文档前言",
                quote,
                null,
                null,
                TEST_TIME
        );
    }

    /**
     * 复制文档标签并替换路径空间，用于跨空间边界测试。
     *
     * @param documentTag 原文档标签
     * @param spaceId 替换后的知识空间标识
     * @return 替换空间后的文档标签
     */
    private DocumentTag copyWithSpace(
            DocumentTag documentTag,
            String spaceId
    ) {
        return new DocumentTag(
                documentTag.id(),
                spaceId,
                documentTag.sourceDocumentId(),
                documentTag.tagId(),
                documentTag.sourceType(),
                documentTag.status(),
                documentTag.confidence(),
                documentTag.extractionRunId(),
                documentTag.contentHash(),
                documentTag.promptVersion(),
                documentTag.schemaVersion(),
                documentTag.documentTagKey(),
                documentTag.createdAt(),
                documentTag.updatedAt()
        );
    }

    /**
     * 复制文档标签并替换内容指纹，用于过期输入边界测试。
     *
     * @param documentTag 原文档标签
     * @param contentHash 替换后的内容指纹
     * @return 替换内容指纹后的文档标签
     */
    private DocumentTag copyWithContentHash(
            DocumentTag documentTag,
            String contentHash
    ) {
        return new DocumentTag(
                documentTag.id(),
                documentTag.spaceId(),
                documentTag.sourceDocumentId(),
                documentTag.tagId(),
                documentTag.sourceType(),
                documentTag.status(),
                documentTag.confidence(),
                documentTag.extractionRunId(),
                contentHash,
                documentTag.promptVersion(),
                documentTag.schemaVersion(),
                documentTag.documentTagKey(),
                documentTag.createdAt(),
                documentTag.updatedAt()
        );
    }
}

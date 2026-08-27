package com.flevin.knowgraph.server.association;

import com.flevin.knowgraph.common.exception.TipsException;
import com.flevin.knowgraph.server.model.association.DocumentAssociationRun;
import com.flevin.knowgraph.server.model.association.DocumentRelation;
import com.flevin.knowgraph.server.model.association.DocumentRelationEvidence;
import com.flevin.knowgraph.server.model.association.DocumentRelationReview;
import com.flevin.knowgraph.server.model.document.DocumentImportResponse;
import com.flevin.knowgraph.server.model.document.SourceDocument;
import com.flevin.knowgraph.server.model.space.CreateKnowledgeSpaceRequest;
import com.flevin.knowgraph.server.model.space.KnowledgeSpaceResponse;
import com.flevin.knowgraph.server.repository.association.DocumentRelationRepository;
import com.flevin.knowgraph.server.repository.document.SourceDocumentRepository;
import com.flevin.knowgraph.server.service.association.DocumentAssociationPersistenceService;
import com.flevin.knowgraph.server.service.document.DocumentService;
import com.flevin.knowgraph.server.service.space.KnowledgeSpaceService;
import com.flevin.knowgraph.server.support.TestKnowledgeSpaceFixtures;
import com.flevin.knowgraph.server.support.TestIdFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文档关联阶段 1 持久化基础集成测试。
 */
@SpringBootTest(properties = {
        "app.upload-dir=target/test-data/document-association-uploads"
})
class DocumentAssociationPersistenceIntegrationTests {

    private static final Long DEFAULT_SPACE_ID = TestKnowledgeSpaceFixtures.DEFAULT_SPACE_ID;
    private static final Instant TEST_TIME = Instant.parse("2026-08-24T01:00:00Z");
    private static final String POLICY_VERSION = "document-association-policy-v1";

    @Autowired
    private DocumentAssociationPersistenceService persistenceService;

    @Autowired
    private DocumentRelationRepository relationRepository;

    @Autowired
    private SourceDocumentRepository sourceDocumentRepository;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private KnowledgeSpaceService knowledgeSpaceService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearAssociationData() {
        // 按外键依赖顺序清理文档关系审核、证据、关系和运行记录
        jdbcTemplate.update("DELETE FROM document_relation_reviews");
        jdbcTemplate.update("DELETE FROM document_relation_evidences");
        jdbcTemplate.update("DELETE FROM document_relations");
        jdbcTemplate.update("DELETE FROM document_association_runs");

        // 清理现有实体图谱和来源资料，避免固定测试数据库污染文档关联样本
        jdbcTemplate.update("DELETE FROM review_actions");
        jdbcTemplate.update("DELETE FROM evidences");
        jdbcTemplate.update("DELETE FROM graph_edges");
        jdbcTemplate.update("DELETE FROM graph_nodes");
        jdbcTemplate.update("DELETE FROM source_documents");
        jdbcTemplate.update("DELETE FROM import_batches");

        // 清理非默认空间并恢复固定测试空间
        jdbcTemplate.update("DELETE FROM knowledge_spaces WHERE id <> ?", DEFAULT_SPACE_ID);
        TestKnowledgeSpaceFixtures.ensureDefaultSpace(jdbcTemplate);
    }

    @Test
    void createsAssociationTablesAndPersistsRunRelationEvidenceAndReviewHistory() {
        // 查询当前 MySQL 中的文档关联表，确认数据库初始化已经承接阶段 1 领域
        Set<String> tableNames = Set.copyOf(jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()",
                String.class
        ));
        assertThat(tableNames).contains(
                "document_association_runs",
                "document_relations",
                "document_relation_evidences",
                "document_relation_reviews"
        );

        SourceDocument sourceDocument = importDocument(
                DEFAULT_SPACE_ID,
                "第一次筹备会议纪要.md",
                "本次会议依据《2026 星桥科技年会活动方案（v1）》讨论场地。"
        );
        SourceDocument targetDocument = importDocument(
                DEFAULT_SPACE_ID,
                "2026 星桥科技年会活动方案-v1.md",
                "2026 星桥科技年会活动方案（v1）\n活动暂定于 2026 年 10 月 18 日举行。"
        );

        DocumentAssociationRun run = newRun(sourceDocument);

        // 保存关联运行快照，验证主体文档内容指纹和版本字段可以恢复
        persistenceService.saveRun(run);

        String relationKey = relationKey(
                sourceDocument.id(),
                targetDocument.id(),
                "references",
                "current_to_candidate",
                sourceDocument.contentHash(),
                targetDocument.contentHash(),
                POLICY_VERSION
        );
        DocumentRelation relation = new DocumentRelation(
                TestIdFixtures.id("relation-reference-1"),
                DEFAULT_SPACE_ID,
                sourceDocument.id(),
                targetDocument.id(),
                "references",
                "current_to_candidate",
                "suggested",
                "explicit_reference",
                0.91,
                "会议纪要明确引用了年会活动方案。",
                run.id(),
                sourceDocument.contentHash(),
                targetDocument.contentHash(),
                POLICY_VERSION,
                relationKey,
                TEST_TIME,
                TEST_TIME
        );

        // 保存候选关系，验证运行归属、关系方向和稳定幂等键
        DocumentRelation savedRelation = persistenceService.saveRelation(relation);
        assertThat(relationRepository.findById(DEFAULT_SPACE_ID, savedRelation.id())).contains(savedRelation);

        String sourceQuote = "本次会议依据《2026 星桥科技年会活动方案（v1）》讨论场地。";
        String targetQuote = "2026 星桥科技年会活动方案（v1）";

        // 保存主体文档证据，验证原文逐字反查和偏移边界
        persistenceService.saveEvidence(new DocumentRelationEvidence(
                TestIdFixtures.id("evidence-reference-source"),
                DEFAULT_SPACE_ID,
                savedRelation.id(),
                sourceDocument.id(),
                "section-1-chunk-1",
                "文档前言",
                sourceQuote,
                sourceDocument.contentText().indexOf(sourceQuote),
                sourceDocument.contentText().indexOf(sourceQuote) + sourceQuote.length(),
                "source",
                TEST_TIME
        ));

        // 保存客体文档证据，验证关系两端证据都能恢复
        persistenceService.saveEvidence(new DocumentRelationEvidence(
                TestIdFixtures.id("evidence-reference-target"),
                DEFAULT_SPACE_ID,
                savedRelation.id(),
                targetDocument.id(),
                "section-1-chunk-1",
                "文档前言",
                targetQuote,
                targetDocument.contentText().indexOf(targetQuote),
                targetDocument.contentText().indexOf(targetQuote) + targetQuote.length(),
                "target",
                TEST_TIME.plusSeconds(1)
        ));

        // 采纳候选关系，验证状态更新和不可变审核历史同时提交
        DocumentRelationReview review = persistenceService.reviewRelation(
                DEFAULT_SPACE_ID,
                savedRelation.id(),
                "accept",
                "两份资料存在明确引用关系",
                "local-user"
        );

        assertThat(relationRepository.findById(DEFAULT_SPACE_ID, savedRelation.id()))
                .get()
                .extracting(DocumentRelation::status)
                .isEqualTo("confirmed");
        assertThat(persistenceService.listEvidence(DEFAULT_SPACE_ID, savedRelation.id()))
                .extracting(DocumentRelationEvidence::sourceDocumentId)
                .containsExactly(sourceDocument.id(), targetDocument.id());
        assertThat(persistenceService.listReviews(DEFAULT_SPACE_ID, savedRelation.id()))
                .extracting(DocumentRelationReview::id)
                .containsExactly(review.id());

        String rejectedRelationKey = relationKey(
                sourceDocument.id(),
                targetDocument.id(),
                "supports",
                "current_to_candidate",
                sourceDocument.contentHash(),
                targetDocument.contentHash(),
                POLICY_VERSION
        );
        DocumentRelation rejectedRelation = persistenceService.saveRelation(new DocumentRelation(
                TestIdFixtures.id("relation-supports-1"),
                DEFAULT_SPACE_ID,
                sourceDocument.id(),
                targetDocument.id(),
                "supports",
                "current_to_candidate",
                "suggested",
                "keyword_match",
                0.42,
                "仅关键词命中，暂不确认支撑关系。",
                run.id(),
                sourceDocument.contentHash(),
                targetDocument.contentHash(),
                POLICY_VERSION,
                rejectedRelationKey,
                TEST_TIME.plusSeconds(2),
                TEST_TIME.plusSeconds(2)
        ));

        // 拒绝候选关系，验证 suggested 到 rejected 的状态迁移和历史记录
        DocumentRelationReview rejectedReview = persistenceService.reviewRelation(
                DEFAULT_SPACE_ID,
                rejectedRelation.id(),
                "reject",
                "只有关键词命中，缺少直接依据",
                "local-user"
        );
        assertThat(relationRepository.findById(DEFAULT_SPACE_ID, rejectedRelation.id()))
                .get()
                .extracting(DocumentRelation::status)
                .isEqualTo("rejected");
        assertThat(persistenceService.listReviews(DEFAULT_SPACE_ID, rejectedRelation.id()))
                .extracting(DocumentRelationReview::id)
                .containsExactly(rejectedReview.id());
    }

    @Test
    void normalizesSymmetricRelationAndRejectsDuplicateStableKey() {
        SourceDocument firstDocument = importDocument(
                DEFAULT_SPACE_ID,
                "甲文档.md",
                "甲文档与乙文档都讨论星桥年会。"
        );
        SourceDocument secondDocument = importDocument(
                DEFAULT_SPACE_ID,
                "乙文档.md",
                "乙文档与甲文档都讨论星桥年会。"
        );
        DocumentAssociationRun run = newRun(firstDocument);
        persistenceService.saveRun(run);

        String relationKey = relationKey(
                firstDocument.id(),
                secondDocument.id(),
                "related_to",
                "symmetric",
                firstDocument.contentHash(),
                secondDocument.contentHash(),
                POLICY_VERSION
        );
        DocumentRelation reversedRelation = new DocumentRelation(
                TestIdFixtures.id("relation-related-1"),
                DEFAULT_SPACE_ID,
                secondDocument.id(),
                firstDocument.id(),
                "related_to",
                "symmetric",
                "suggested",
                "keyword_match",
                0.8,
                "两份文档围绕同一场年会主题。",
                run.id(),
                secondDocument.contentHash(),
                firstDocument.contentHash(),
                POLICY_VERSION,
                relationKey,
                TEST_TIME,
                TEST_TIME
        );

        // 保存反向提交的对称关系，验证服务端规范化主体、客体和内容指纹
        DocumentRelation normalizedRelation = persistenceService.saveRelation(reversedRelation);
        assertThat(normalizedRelation.sourceDocumentId())
                .isLessThan(normalizedRelation.targetDocumentId());
        assertThat(normalizedRelation.sourceContentHash())
                .isEqualTo(firstDocument.id().compareTo(secondDocument.id()) < 0
                        ? firstDocument.contentHash()
                        : secondDocument.contentHash());

        DocumentRelation duplicateRelation = new DocumentRelation(
                TestIdFixtures.id("relation-related-2"),
                DEFAULT_SPACE_ID,
                firstDocument.id(),
                secondDocument.id(),
                "related_to",
                "symmetric",
                "suggested",
                "keyword_match",
                0.8,
                "重复的同主题关系。",
                run.id(),
                firstDocument.contentHash(),
                secondDocument.contentHash(),
                POLICY_VERSION,
                relationKey,
                TEST_TIME.plusSeconds(1),
                TEST_TIME.plusSeconds(1)
        );

        // 相同规范化关系键重复保存必须返回冲突，而不是增加第二条建议
        assertThatThrownBy(() -> persistenceService.saveRelation(duplicateRelation))
                .isInstanceOf(TipsException.class)
                .hasMessage("相同版本的文档关系已经存在");
    }

    @Test
    void rejectsDuplicateDirectedRelationWhenReverseRunUsesRelativeDirection() {
        SourceDocument sourceDocument = importDocument(
                DEFAULT_SPACE_ID,
                "引用主体.md",
                "引用主体明确引用了目标资料。"
        );
        SourceDocument targetDocument = importDocument(
                DEFAULT_SPACE_ID,
                "引用目标.md",
                "引用目标保存被引用的业务结论。"
        );
        DocumentAssociationRun sourceRun = newRun(sourceDocument);
        DocumentAssociationRun targetRun = newRun(targetDocument);

        // 保存关系两端各自发起的运行，模拟同一关系从正反方向被召回
        persistenceService.saveRun(sourceRun);
        persistenceService.saveRun(targetRun);

        DocumentRelation firstSuggestion = new DocumentRelation(
                TestIdFixtures.id("relation-directed-forward"),
                DEFAULT_SPACE_ID,
                sourceDocument.id(),
                targetDocument.id(),
                "references",
                "current_to_candidate",
                "suggested",
                "explicit_reference",
                0.9,
                "主体文档明确引用目标资料。",
                sourceRun.id(),
                sourceDocument.contentHash(),
                targetDocument.contentHash(),
                POLICY_VERSION,
                null,
                TEST_TIME,
                TEST_TIME
        );

        // 首次从关系主体运行时保存唯一有向关系
        persistenceService.saveRelation(firstSuggestion);

        DocumentRelation reverseRunSuggestion = new DocumentRelation(
                TestIdFixtures.id("relation-directed-reverse-run"),
                DEFAULT_SPACE_ID,
                sourceDocument.id(),
                targetDocument.id(),
                "references",
                "candidate_to_current",
                "suggested",
                "explicit_reference",
                0.9,
                "目标资料运行时仍识别到同一引用关系。",
                targetRun.id(),
                sourceDocument.contentHash(),
                targetDocument.contentHash(),
                POLICY_VERSION,
                null,
                TEST_TIME.plusSeconds(1),
                TEST_TIME.plusSeconds(1)
        );

        // 相对运行方向不同不能改变同一业务关系的稳定幂等键
        assertThatThrownBy(() -> persistenceService.saveRelation(reverseRunSuggestion))
                .isInstanceOf(TipsException.class)
                .hasMessage("相同版本的文档关系已经存在");
    }

    @Test
    void rejectsCrossSpaceSelfRelationInvalidDirectionAndUntraceableEvidence() {
        SourceDocument sourceDocument = importDocument(
                DEFAULT_SPACE_ID,
                "主体文档.md",
                "主体文档引用了客体文档。"
        );
        SourceDocument targetDocument = importDocument(
                DEFAULT_SPACE_ID,
                "客体文档.md",
                "客体文档记录了被引用的会议结论。"
        );
        KnowledgeSpaceResponse otherSpace = knowledgeSpaceService.createSpace(
                new CreateKnowledgeSpaceRequest("关联隔离空间", "文档关系跨空间测试")
        );
        SourceDocument otherDocument = importDocument(
                otherSpace.id(),
                "其他空间文档.md",
                "其他空间内容。"
        );
        DocumentAssociationRun run = newRun(sourceDocument);
        persistenceService.saveRun(run);

        String crossSpaceKey = relationKey(
                sourceDocument.id(),
                otherDocument.id(),
                "references",
                "current_to_candidate",
                sourceDocument.contentHash(),
                otherDocument.contentHash(),
                POLICY_VERSION
        );
        DocumentRelation crossSpaceRelation = new DocumentRelation(
                TestIdFixtures.id("relation-cross-space"),
                DEFAULT_SPACE_ID,
                sourceDocument.id(),
                otherDocument.id(),
                "references",
                "current_to_candidate",
                "suggested",
                "explicit_reference",
                0.7,
                "非法跨空间关系。",
                run.id(),
                sourceDocument.contentHash(),
                otherDocument.contentHash(),
                POLICY_VERSION,
                crossSpaceKey,
                TEST_TIME,
                TEST_TIME
        );

        // 跨空间关系必须在查询客体文档时被拒绝
        assertThatThrownBy(() -> persistenceService.saveRelation(crossSpaceRelation))
                .isInstanceOf(TipsException.class)
                .hasMessage("来源资料不存在或已删除");

        String selfKey = relationKey(
                sourceDocument.id(),
                sourceDocument.id(),
                "related_to",
                "symmetric",
                sourceDocument.contentHash(),
                sourceDocument.contentHash(),
                POLICY_VERSION
        );
        DocumentRelation selfRelation = new DocumentRelation(
                TestIdFixtures.id("relation-self"),
                DEFAULT_SPACE_ID,
                sourceDocument.id(),
                sourceDocument.id(),
                "related_to",
                "symmetric",
                "suggested",
                "keyword_match",
                0.7,
                "非法自关联。",
                run.id(),
                sourceDocument.contentHash(),
                sourceDocument.contentHash(),
                POLICY_VERSION,
                selfKey,
                TEST_TIME,
                TEST_TIME
        );

        // 自关联必须在任何数据库写入前被拒绝
        assertThatThrownBy(() -> persistenceService.saveRelation(selfRelation))
                .isInstanceOf(TipsException.class)
                .hasMessage("文档关系不能引用自身");

        String invalidDirectionKey = relationKey(
                sourceDocument.id(),
                otherSpace.id(),
                "related_to",
                "current_to_candidate",
                sourceDocument.contentHash(),
                sourceDocument.contentHash(),
                POLICY_VERSION
        );
        DocumentRelation invalidDirection = new DocumentRelation(
                TestIdFixtures.id("relation-invalid-direction"),
                DEFAULT_SPACE_ID,
                sourceDocument.id(),
                TestIdFixtures.id("relation-invalid-direction-target"),
                "related_to",
                "current_to_candidate",
                "suggested",
                "keyword_match",
                0.7,
                "非法对称关系方向。",
                run.id(),
                sourceDocument.contentHash(),
                sourceDocument.contentHash(),
                POLICY_VERSION,
                invalidDirectionKey,
                TEST_TIME,
                TEST_TIME
        );

        // related_to 只能使用 symmetric 方向，不能降级为有向关系
        assertThatThrownBy(() -> persistenceService.saveRelation(invalidDirection))
                .isInstanceOf(TipsException.class)
                .hasMessage("对称文档关系必须使用 symmetric 方向");

        String validRelationKey = relationKey(
                sourceDocument.id(),
                targetDocument.id(),
                "references",
                "current_to_candidate",
                sourceDocument.contentHash(),
                targetDocument.contentHash(),
                POLICY_VERSION
        );
        DocumentRelation validRelation = new DocumentRelation(
                TestIdFixtures.id("relation-evidence-validation"),
                DEFAULT_SPACE_ID,
                sourceDocument.id(),
                targetDocument.id(),
                "references",
                "current_to_candidate",
                "suggested",
                "explicit_reference",
                0.7,
                "占位关系。",
                run.id(),
                sourceDocument.contentHash(),
                targetDocument.contentHash(),
                POLICY_VERSION,
                validRelationKey,
                TEST_TIME,
                TEST_TIME
        );

        // 保存真实两端文档关系，为后续证据逐字反查准备有效外键
        persistenceService.saveRelation(validRelation);

        // 证据原文不在来源资料中时必须被拒绝，不能只依赖模型返回的 quote
        assertThatThrownBy(() -> persistenceService.saveEvidence(new DocumentRelationEvidence(
                TestIdFixtures.id("evidence-invalid-quote"),
                DEFAULT_SPACE_ID,
                validRelation.id(),
                sourceDocument.id(),
                "section-1-chunk-1",
                "文档前言",
                "这段文字不存在于主体文档。",
                null,
                null,
                "source",
                TEST_TIME
        )))
                .isInstanceOf(TipsException.class)
                .hasMessage("文档关系证据无法在指定分片中逐字反查");
    }

    @Test
    void rejectsEvidenceRoleWhenItDoesNotMatchEvidenceDocument() {
        SourceDocument sourceDocument = importDocument(
                DEFAULT_SPACE_ID,
                "角色主体文档.md",
                "主体文档明确引用客体文档。"
        );
        SourceDocument targetDocument = importDocument(
                DEFAULT_SPACE_ID,
                "角色客体文档.md",
                "客体文档保存被引用的结论。"
        );
        DocumentAssociationRun run = newRun(sourceDocument);
        persistenceService.saveRun(run);
        String relationKey = relationKey(
                sourceDocument.id(),
                targetDocument.id(),
                "references",
                "current_to_candidate",
                sourceDocument.contentHash(),
                targetDocument.contentHash(),
                POLICY_VERSION
        );
        DocumentRelation relation = persistenceService.saveRelation(new DocumentRelation(
                TestIdFixtures.id("relation-role-validation"),
                DEFAULT_SPACE_ID,
                sourceDocument.id(),
                targetDocument.id(),
                "references",
                "current_to_candidate",
                "suggested",
                "explicit_reference",
                0.8,
                "主体文档引用客体文档。",
                run.id(),
                sourceDocument.contentHash(),
                targetDocument.contentHash(),
                POLICY_VERSION,
                relationKey,
                TEST_TIME,
                TEST_TIME
        ));
        String targetQuote = "客体文档保存被引用的结论。";

        // target 角色不能指向主体文档，避免 UI 将证据方向解释反
        assertThatThrownBy(() -> persistenceService.saveEvidence(new DocumentRelationEvidence(
                TestIdFixtures.id("evidence-role-mismatch"),
                DEFAULT_SPACE_ID,
                relation.id(),
                sourceDocument.id(),
                "section-1-chunk-1",
                "文档前言",
                "主体文档明确引用客体文档。",
                0,
                "主体文档明确引用客体文档。".length(),
                "target",
                TEST_TIME
        )))
                .isInstanceOf(TipsException.class)
                .hasMessage("target 证据必须来自关系客体文档");

        // cross_reference 可以来自关系任一端，只要求原文逐字存在
        DocumentRelationEvidence crossReference = persistenceService.saveEvidence(
                new DocumentRelationEvidence(
                        TestIdFixtures.id("evidence-cross-reference"),
                        DEFAULT_SPACE_ID,
                        relation.id(),
                        targetDocument.id(),
                        "section-1-chunk-1",
                        "文档前言",
                        targetQuote,
                        0,
                        targetQuote.length(),
                        "cross_reference",
                        TEST_TIME
                )
        );
        assertThat(crossReference.evidenceRole()).isEqualTo("cross_reference");
    }

    private SourceDocument importDocument(
            Long spaceId,
            String fileName,
            String content
    ) {
        MockMultipartFile file = new MockMultipartFile(
                "files",
                fileName,
                "text/markdown",
                content.getBytes(StandardCharsets.UTF_8)
        );

        // 通过现有来源资料导入链路创建真实文档和内容指纹
        DocumentImportResponse response = documentService.importDocuments(spaceId, List.of(file));
        Long documentId = response.results().getFirst().document().id();

        // 读取完整来源资料，供阶段 1 证据逐字反查使用
        return sourceDocumentRepository.findById(spaceId, documentId).orElseThrow();
    }

    private DocumentAssociationRun newRun(SourceDocument sourceDocument) {
        return new DocumentAssociationRun(
                TestIdFixtures.id("run-" + sourceDocument.id()),
                DEFAULT_SPACE_ID,
                sourceDocument.id(),
                sourceDocument.contentHash(),
                "completed",
                null,
                null,
                2,
                2,
                1,
                0,
                2,
                0,
                "document-association-v1",
                "document-association-v1",
                "document-candidate-recall-v1",
                POLICY_VERSION,
                null,
                null,
                null,
                8,
                null,
                1,
                0,
                120L,
                TEST_TIME,
                TEST_TIME.plusSeconds(1)
        );
    }

    private String relationKey(
            Long sourceDocumentId,
            Long targetDocumentId,
            String relationType,
            String direction,
            String sourceContentHash,
            String targetContentHash,
            String policyVersion
    ) {
        Long leftDocumentId = sourceDocumentId;
        Long rightDocumentId = targetDocumentId;
        String leftHash = sourceContentHash;
        String rightHash = targetContentHash;
        if ("symmetric".equals(direction) && leftDocumentId.compareTo(rightDocumentId) > 0) {
            leftDocumentId = targetDocumentId;
            rightDocumentId = sourceDocumentId;
            leftHash = targetContentHash;
            rightHash = sourceContentHash;
        }
        String rawKey = String.join(
                "|",
                String.valueOf(leftDocumentId),
                relationType,
                String.valueOf(rightDocumentId),
                leftHash,
                rightHash,
                policyVersion
        );
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(rawKey.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}

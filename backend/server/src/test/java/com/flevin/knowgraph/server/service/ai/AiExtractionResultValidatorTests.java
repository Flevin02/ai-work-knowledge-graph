package com.flevin.knowgraph.server.service.ai;

import com.flevin.knowgraph.server.model.ai.AiEntityCandidate;
import com.flevin.knowgraph.server.model.ai.AiEntityType;
import com.flevin.knowgraph.server.model.ai.AiEvidenceCandidate;
import com.flevin.knowgraph.server.model.ai.AiExtractionRequest;
import com.flevin.knowgraph.server.model.ai.AiExtractionResult;
import com.flevin.knowgraph.server.model.ai.AiRelationCandidate;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiExtractionResultValidatorTests {

    @Test
    void acceptsResultWhoseReferencesAndQuotesMatchSourceChunk() {
        AiExtractionRequest request = request();
        AiExtractionResult result = validResult("用户中心包含登录功能");

        try (ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory()) {
            AiExtractionResultValidator validator = new AiExtractionResultValidator(
                    validatorFactory.getValidator()
            );

            // 校验结构、关系引用和逐字原文证据
            AiExtractionResult validatedResult = validator.validate(request, result);

            assertThat(validatedResult).isSameAs(result);
        }
    }

    @Test
    void rejectsEvidenceQuoteThatCannotBeFoundInSourceChunk() {
        AiExtractionRequest request = request();
        AiExtractionResult result = validResult("原文中不存在的模型幻觉引用");

        try (ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory()) {
            AiExtractionResultValidator validator = new AiExtractionResultValidator(
                    validatorFactory.getValidator()
            );

            // 验证模型改写或臆造的引用不能成为图谱证据
            assertThatThrownBy(() -> validator.validate(request, result))
                    .isInstanceOf(AiExtractionValidationException.class)
                    .hasMessageContaining("证据无法在原文中逐字定位");
        }
    }

    @Test
    void rejectsRelationThatReferencesMissingEntity() {
        AiExtractionRequest request = request();
        AiExtractionResult validResult = validResult("用户中心包含登录功能");
        AiRelationCandidate invalidRelation = new AiRelationCandidate(
                "entity-missing",
                "entity-feature",
                "project_contains_feature",
                0.9D,
                List.of("evidence-1")
        );
        AiExtractionResult invalidResult = new AiExtractionResult(
                validResult.summary(),
                validResult.entities(),
                List.of(invalidRelation),
                validResult.evidences(),
                List.of()
        );

        try (ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory()) {
            AiExtractionResultValidator validator = new AiExtractionResultValidator(
                    validatorFactory.getValidator()
            );

            // 验证关系不能引用模型未输出的候选实体
            assertThatThrownBy(() -> validator.validate(request, invalidResult))
                    .isInstanceOf(AiExtractionValidationException.class)
                    .hasMessageContaining("不存在的主体实体");
        }
    }

    @Test
    void rejectsRelationTypeOutsideBusinessWhitelist() {
        AiExtractionRequest request = request();
        AiExtractionResult validResult = validResult("用户中心包含登录功能");
        AiRelationCandidate unknownRelation = new AiRelationCandidate(
                "entity-project",
                "entity-feature",
                "project_owns_feature",
                0.9D,
                List.of("evidence-1")
        );
        AiExtractionResult invalidResult = new AiExtractionResult(
                validResult.summary(),
                validResult.entities(),
                List.of(unknownRelation),
                validResult.evidences(),
                validResult.conflicts()
        );

        try (ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory()) {
            AiExtractionResultValidator validator = new AiExtractionResultValidator(
                    validatorFactory.getValidator()
            );

            // 验证格式合法但未进入业务白名单的模型关系不会进入候选图谱
            assertThatThrownBy(() -> validator.validate(request, invalidResult))
                    .isInstanceOf(AiExtractionValidationException.class)
                    .hasMessageContaining("不支持的关系类型: project_owns_feature");
        }
    }

    @Test
    void rejectsRelationWhoseEntityTypesDoNotMatchDirectionRule() {
        AiExtractionRequest request = request();
        AiExtractionResult validResult = validResult("用户中心包含登录功能");
        AiRelationCandidate reversedRelation = new AiRelationCandidate(
                "entity-feature",
                "entity-project",
                "project_contains_feature",
                0.9D,
                List.of("evidence-1")
        );
        AiExtractionResult invalidResult = new AiExtractionResult(
                validResult.summary(),
                validResult.entities(),
                List.of(reversedRelation),
                validResult.evidences(),
                validResult.conflicts()
        );

        try (ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory()) {
            AiExtractionResultValidator validator = new AiExtractionResultValidator(
                    validatorFactory.getValidator()
            );

            // 验证白名单关系仍必须满足固定主体、客体类型和方向
            assertThatThrownBy(() -> validator.validate(request, invalidResult))
                    .isInstanceOf(AiExtractionValidationException.class)
                    .hasMessageContaining(
                            "关系主体/客体类型不匹配: project_contains_feature 要求 PROJECT -> FEATURE，实际 FEATURE -> PROJECT"
                    );
        }
    }

    @Test
    void rejectsBlankRequestBeforeRemoteModelCanBeCalled() {
        AiExtractionRequest invalidRequest = new AiExtractionRequest(
                "document-1",
                "用户中心 PRD.md",
                "prd",
                "section-1-chunk-1",
                "用户中心 > 登录功能",
                " "
        );

        try (ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory()) {
            AiExtractionResultValidator validator = new AiExtractionResultValidator(
                    validatorFactory.getValidator()
            );

            // 验证空白原文在产生远程调用和费用前被拒绝
            assertThatThrownBy(() -> validator.validateRequest(invalidRequest))
                    .isInstanceOf(AiExtractionValidationException.class)
                    .hasMessageContaining("content");
        }
    }

    @Test
    void rejectsBlankChunkSummaryThroughBeanValidation() {
        AiExtractionRequest request = request();
        AiExtractionResult validResult = validResult("用户中心包含登录功能");
        AiExtractionResult invalidResult = new AiExtractionResult(
                " ",
                validResult.entities(),
                validResult.relations(),
                validResult.evidences(),
                validResult.conflicts()
        );

        try (ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory()) {
            AiExtractionResultValidator validator = new AiExtractionResultValidator(
                    validatorFactory.getValidator()
            );

            // 验证空白摘要由 Jakarta Validation 在业务引用校验前统一拒绝
            assertThatThrownBy(() -> validator.validate(request, invalidResult))
                    .isInstanceOf(AiExtractionValidationException.class)
                    .hasMessageContaining("分片摘要不能为空");
        }
    }

    @Test
    void rejectsChunkSummaryLongerThanCardBoundary() {
        AiExtractionRequest request = request();
        AiExtractionResult validResult = validResult("用户中心包含登录功能");
        AiExtractionResult invalidResult = new AiExtractionResult(
                "摘".repeat(161),
                validResult.entities(),
                validResult.relations(),
                validResult.evidences(),
                validResult.conflicts()
        );

        try (ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory()) {
            AiExtractionResultValidator validator = new AiExtractionResultValidator(
                    validatorFactory.getValidator()
            );

            // 验证超长摘要由 Jakarta Validation 按结构化输出边界统一拒绝
            assertThatThrownBy(() -> validator.validate(request, invalidResult))
                    .isInstanceOf(AiExtractionValidationException.class)
                    .hasMessageContaining("分片摘要不能超过 160 个字符");
        }
    }

    @Test
    void acceptsEmptyEntitySummaryWithinZeroToOneHundredSixtyCharacterBoundary() {
        AiExtractionRequest request = request();
        AiExtractionResult validResult = validResult("用户中心包含登录功能");
        AiEntityCandidate projectWithoutSummary = new AiEntityCandidate(
                "entity-project",
                AiEntityType.PROJECT,
                "用户中心",
                "",
                List.of("evidence-1")
        );
        AiExtractionResult resultWithEmptySummary = new AiExtractionResult(
                validResult.summary(),
                List.of(projectWithoutSummary, validResult.entities().get(1)),
                validResult.relations(),
                validResult.evidences(),
                validResult.conflicts()
        );

        try (ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory()) {
            AiExtractionResultValidator validator = new AiExtractionResultValidator(
                    validatorFactory.getValidator()
            );

            // 验证原文信息不足时允许实体保留空摘要，后续物化会回退为实体名称
            assertThat(validator.validate(request, resultWithEmptySummary)).isSameAs(resultWithEmptySummary);
        }
    }

    @Test
    void rejectsEntitySummaryLongerThanOneHundredSixtyCharacters() {
        AiExtractionRequest request = request();
        AiExtractionResult validResult = validResult("用户中心包含登录功能");
        AiEntityCandidate invalidProject = new AiEntityCandidate(
                "entity-project",
                AiEntityType.PROJECT,
                "用户中心",
                "超".repeat(161),
                List.of("evidence-1")
        );
        AiExtractionResult invalidResult = new AiExtractionResult(
                validResult.summary(),
                List.of(invalidProject, validResult.entities().get(1)),
                validResult.relations(),
                validResult.evidences(),
                validResult.conflicts()
        );

        try (ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory()) {
            AiExtractionResultValidator validator = new AiExtractionResultValidator(
                    validatorFactory.getValidator()
            );

            // 验证实体摘要超过 160 个字符时不会进入候选图谱物化流程
            assertThatThrownBy(() -> validator.validate(request, invalidResult))
                    .isInstanceOf(AiExtractionValidationException.class)
                    .hasMessageContaining("实体摘要不能超过 160 个字符");
        }
    }

    private AiExtractionRequest request() {
        return new AiExtractionRequest(
                "document-1",
                "用户中心 PRD.md",
                "prd",
                "section-1-chunk-1",
                "用户中心 > 登录功能",
                "用户中心包含登录功能，登录功能支持手机号验证码。"
        );
    }

    private AiExtractionResult validResult(String quote) {
        String entitySummary = "用户中心包含登录功能，登录功能支持手机号验证码登录。".repeat(4);
        AiEvidenceCandidate evidence = new AiEvidenceCandidate(
                "evidence-1",
                "document-1",
                "section-1-chunk-1",
                "用户中心 > 登录功能",
                quote
        );
        AiEntityCandidate project = new AiEntityCandidate(
                "entity-project",
                AiEntityType.PROJECT,
                "用户中心",
                entitySummary,
                List.of("evidence-1")
        );
        AiEntityCandidate feature = new AiEntityCandidate(
                "entity-feature",
                AiEntityType.FEATURE,
                "登录功能",
                entitySummary,
                List.of("evidence-1")
        );
        AiRelationCandidate relation = new AiRelationCandidate(
                "entity-project",
                "entity-feature",
                "project_contains_feature",
                0.9D,
                List.of("evidence-1")
        );
        return new AiExtractionResult(
                "用户中心包含登录功能并支持手机号验证码。",
                List.of(project, feature),
                List.of(relation),
                List.of(evidence),
                List.of()
        );
    }
}

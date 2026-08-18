package com.flevin.knowgraph.server.service.ai;

import com.flevin.knowgraph.server.model.ai.AiConflictCandidate;
import com.flevin.knowgraph.server.model.ai.AiEntityCandidate;
import com.flevin.knowgraph.server.model.ai.AiEvidenceCandidate;
import com.flevin.knowgraph.server.model.ai.AiExtractionRequest;
import com.flevin.knowgraph.server.model.ai.AiExtractionResult;
import com.flevin.knowgraph.server.model.ai.AiRelationCandidate;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * AI 抽取结果校验器，负责 Bean Validation、引用完整性和原文证据反查。
 */
@Component
@RequiredArgsConstructor
public class AiExtractionResultValidator {

    private final Validator validator;

    /**
     * 在调用远程模型前校验来源定位和原文输入。
     *
     * @param request 待发送给模型的抽取请求
     * @throws AiExtractionValidationException 请求字段不合法时抛出
     */
    public void validateRequest(AiExtractionRequest request) {
        if (request == null) {
            throw new AiExtractionValidationException("AI 抽取请求不能为空");
        }

        // 在产生远程调用和费用前执行请求字段校验
        Set<ConstraintViolation<AiExtractionRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            // 汇总稳定的字段路径和校验消息，保留输入错误上下文
            String message = violations.stream()
                    .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                    .sorted()
                    .collect(Collectors.joining("; "));
            throw new AiExtractionValidationException("AI 抽取请求校验失败: " + message);
        }
    }

    /**
     * 校验模型结果并原样返回，便于供应商适配层形成统一出口。
     *
     * @param request 当前来源分片请求
     * @param result 模型结构化结果
     * @return 已通过全部校验的结果
     * @throws AiExtractionValidationException 结构、引用或证据不合法时抛出
     */
    public AiExtractionResult validate(
            AiExtractionRequest request,
            AiExtractionResult result
    ) {
        // 复用输入校验，保证证据反查使用完整来源定位
        validateRequest(request);

        if (result == null) {
            throw new AiExtractionValidationException("AI 未返回结构化抽取结果");
        }

        // 执行 DTO 字段级 Bean Validation
        Set<ConstraintViolation<AiExtractionResult>> violations = validator.validate(result);
        if (!violations.isEmpty()) {
            // 汇总稳定的字段路径和校验消息，保留结构错误上下文
            String message = violations.stream()
                    .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                    .sorted()
                    .collect(Collectors.joining("; "));
            throw new AiExtractionValidationException("AI 结构化输出校验失败: " + message);
        }

        // 校验候选实体标识在本次结果内唯一
        Set<String> entityIds = requireUniqueIds(
                result.entities(),
                AiEntityCandidate::candidateId,
                "候选实体"
        );

        // 校验证据标识在本次结果内唯一
        Set<String> evidenceIds = requireUniqueIds(
                result.evidences(),
                AiEvidenceCandidate::evidenceId,
                "证据"
        );

        // 校验每条证据都能反查到当前来源分片的逐字原文
        result.evidences().forEach(evidence -> validateEvidence(request, evidence));

        // 校验关系引用的实体和证据都存在于本次结构化结果
        result.relations().forEach(relation -> validateRelation(relation, entityIds, evidenceIds));

        // 校验实体引用的证据都存在于本次结构化结果
        result.entities().forEach(entity -> validateEvidenceReferences(
                entity.evidenceIds(),
                evidenceIds,
                "候选实体 " + entity.candidateId()
        ));

        // 校验冲突引用的证据都存在于本次结构化结果
        result.conflicts().forEach(conflict -> validateConflict(conflict, evidenceIds));
        return result;
    }

    /**
     * 提取唯一标识集合，并拒绝同一结果内的重复标识。
     *
     * @param items 待检查项目
     * @param idExtractor 标识提取函数
     * @param description 项目业务名称
     * @param <T> 项目类型
     * @return 唯一标识集合
     */
    private <T> Set<String> requireUniqueIds(
            List<T> items,
            Function<T, String> idExtractor,
            String description
    ) {
        Set<String> ids = new HashSet<>();

        // 找出首次出现的重复标识并保留业务上下文
        items.stream()
                .map(idExtractor)
                .filter(id -> !ids.add(id))
                .findFirst()
                .ifPresent(duplicateId -> {
                    throw new AiExtractionValidationException(
                            description + "标识重复: " + duplicateId
                    );
                });
        return ids;
    }

    /**
     * 校验证据来源定位和原文引用。
     *
     * @param request 当前来源分片请求
     * @param evidence 待校验证据
     */
    private void validateEvidence(
            AiExtractionRequest request,
            AiEvidenceCandidate evidence
    ) {
        if (!request.sourceDocumentId().equals(evidence.sourceDocumentId())) {
            throw new AiExtractionValidationException(
                    "证据来源资料不匹配: " + evidence.evidenceId()
            );
        }
        if (!request.chunkId().equals(evidence.chunkId())) {
            throw new AiExtractionValidationException(
                    "证据分片不匹配: " + evidence.evidenceId()
            );
        }
        if (!request.sectionPath().equals(evidence.sectionPath())) {
            throw new AiExtractionValidationException(
                    "证据章节不匹配: " + evidence.evidenceId()
            );
        }
        if (!request.content().contains(evidence.quote())) {
            throw new AiExtractionValidationException(
                    "证据无法在原文中逐字定位: " + evidence.evidenceId()
            );
        }
    }

    /**
     * 校验候选关系引用的实体和证据标识。
     *
     * @param relation 候选关系
     * @param entityIds 有效候选实体标识
     * @param evidenceIds 有效证据标识
     */
    private void validateRelation(
            AiRelationCandidate relation,
            Set<String> entityIds,
            Set<String> evidenceIds
    ) {
        if (!entityIds.contains(relation.sourceEntityId())) {
            throw new AiExtractionValidationException(
                    "关系引用了不存在的主体实体: " + relation.sourceEntityId()
            );
        }
        if (!entityIds.contains(relation.targetEntityId())) {
            throw new AiExtractionValidationException(
                    "关系引用了不存在的客体实体: " + relation.targetEntityId()
            );
        }

        // 校验关系所需证据引用完整存在
        validateEvidenceReferences(relation.evidenceIds(), evidenceIds, "候选关系");
    }

    /**
     * 校验冲突引用的证据标识。
     *
     * @param conflict 冲突候选
     * @param evidenceIds 有效证据标识
     */
    private void validateConflict(
            AiConflictCandidate conflict,
            Set<String> evidenceIds
    ) {
        // 校验冲突所需证据引用完整存在
        validateEvidenceReferences(
                conflict.evidenceIds(),
                evidenceIds,
                "冲突 " + conflict.conflictType()
        );
    }

    /**
     * 校验一组证据引用全部存在。
     *
     * @param referencedIds 被引用证据标识
     * @param evidenceIds 有效证据标识
     * @param ownerDescription 引用方业务说明
     */
    private void validateEvidenceReferences(
            List<String> referencedIds,
            Set<String> evidenceIds,
            String ownerDescription
    ) {
        // 找出引用方指向的首个不存在证据
        referencedIds.stream()
                .filter(referenceId -> !evidenceIds.contains(referenceId))
                .findFirst()
                .ifPresent(referenceId -> {
                    throw new AiExtractionValidationException(
                            ownerDescription + "引用了不存在的证据: " + referenceId
                    );
                });
    }
}

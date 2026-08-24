package com.flevin.knowgraph.server.model.ai;

import java.util.Arrays;
import java.util.Optional;

/**
 * 首版实体抽取允许产生的固定关系类型及方向约束。
 *
 * <p>该白名单只约束现有实体图谱兼容链路，不代表后续文档关系图的关系定义。</p>
 */
public enum AiRelationType {

    PROJECT_CONTAINS_FEATURE(AiEntityType.PROJECT, AiEntityType.FEATURE),
    FEATURE_CONTAINS_REQUIREMENT(AiEntityType.FEATURE, AiEntityType.REQUIREMENT),
    REQUIREMENT_HAS_TASK(AiEntityType.REQUIREMENT, AiEntityType.TASK),
    REQUIREMENT_HAS_RISK(AiEntityType.REQUIREMENT, AiEntityType.RISK),
    TASK_ASSIGNED_TO_PERSON(AiEntityType.TASK, AiEntityType.PERSON),
    DEPARTMENT_RESPONSIBLE_FOR_PROJECT(AiEntityType.DEPARTMENT, AiEntityType.PROJECT),
    DECISION_AFFECTS_REQUIREMENT(AiEntityType.DECISION, AiEntityType.REQUIREMENT);

    private final AiEntityType sourceType;
    private final AiEntityType targetType;

    AiRelationType(
            AiEntityType sourceType,
            AiEntityType targetType
    ) {
        this.sourceType = sourceType;
        this.targetType = targetType;
    }

    /**
     * 按模型输出的小写关系值查找固定业务关系。
     *
     * @param value 模型输出的关系类型
     * @return 匹配的白名单关系；未知关系返回空
     */
    public static Optional<AiRelationType> fromValue(String value) {
        // 使用枚举名称派生稳定的小写值，避免维护第二份字符串常量
        return Arrays.stream(values())
                .filter(type -> type.value().equals(value))
                .findFirst();
    }

    /**
     * 返回持久化和 API 使用的小写关系值。
     *
     * @return 小写下划线关系值
     */
    public String value() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 判断候选主体和客体是否符合当前关系的固定方向。
     *
     * @param actualSourceType 候选主体实体类型
     * @param actualTargetType 候选客体实体类型
     * @return 类型和方向均匹配时返回 true
     */
    public boolean matches(
            AiEntityType actualSourceType,
            AiEntityType actualTargetType
    ) {
        return sourceType == actualSourceType && targetType == actualTargetType;
    }

    /**
     * 获取关系要求的主体实体类型。
     *
     * @return 固定主体类型
     */
    public AiEntityType sourceType() {
        return sourceType;
    }

    /**
     * 获取关系要求的客体实体类型。
     *
     * @return 固定客体类型
     */
    public AiEntityType targetType() {
        return targetType;
    }
}

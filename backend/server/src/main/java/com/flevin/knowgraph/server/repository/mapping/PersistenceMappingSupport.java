package com.flevin.knowgraph.server.repository.mapping;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flevin.knowgraph.server.model.document.SourceDocumentType;
import org.mapstruct.Named;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 持久化模型映射共享的基础类型转换器。
 */
@Component
public class PersistenceMappingSupport {

    private static final TypeReference<List<Long>> LONG_LIST_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public PersistenceMappingSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 将数据库 ISO-8601 时间文本转换为领域时间。
     *
     * @param value 数据库时间文本
     * @return 领域时间；空值保持为空
     */
    @Named("stringToInstant")
    public Instant stringToInstant(String value) {
        // 解析数据库统一保存的 ISO-8601 时间文本
        return value == null ? null : Instant.parse(value);
    }

    /**
     * 将领域时间转换为数据库 ISO-8601 时间文本。
     *
     * @param value 领域时间
     * @return 数据库时间文本；空值保持为空
     */
    @Named("instantToString")
    public String instantToString(Instant value) {
        // 使用 Instant 标准文本形式写回数据库字段
        return value == null ? null : value.toString();
    }

    /**
     * 将可空数据库统计值转换为领域整数。
     *
     * @param value 数据库统计值
     * @return 统计值；历史空值按零处理
     */
    @Named("integerToInt")
    public int integerToInt(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * 将可空数据库浮点值转换为领域浮点数。
     *
     * @param value 数据库浮点值
     * @return 浮点值；历史空值按零处理
     */
    @Named("doubleToPrimitive")
    public double doubleToPrimitive(Double value) {
        return value == null ? 0D : value;
    }

    /**
     * 将可空数据库长整数转换为领域长整数。
     *
     * @param value 数据库长整数
     * @return 长整数；历史空值按零处理
     */
    @Named("longToPrimitive")
    public long longToPrimitive(Long value) {
        return value == null ? 0L : value;
    }

    /**
     * 将数据库文档类型文本转换为领域枚举。
     *
     * @param value 数据库文档类型文本
     * @return 文档类型；未知或空值按 general 兼容
     */
    @Named("stringToDocumentType")
    public SourceDocumentType stringToDocumentType(String value) {
        // 保持历史数据兼容，未知文档类型沿用 general 回退规则
        return SourceDocumentType.fromValue(value).orElse(SourceDocumentType.GENERAL);
    }

    /**
     * 将领域文档类型转换为数据库文本。
     *
     * @param value 文档类型
     * @return 数据库文本；空值保持为空
     */
    @Named("documentTypeToString")
    public String documentTypeToString(SourceDocumentType value) {
        // 写入数据库和接口统一使用的小写文档类型值
        return value == null ? null : value.getValue();
    }

    /**
     * 将来源资料标识 JSON 数组转换为列表。
     *
     * @param value 来源资料标识 JSON
     * @return 来源资料标识列表
     */
    @Named("jsonToLongList")
    public List<Long> jsonToLongList(String value) {
        try {
            // 使用项目统一 ObjectMapper 解析来源资料标识
            return objectMapper.readValue(value, LONG_LIST_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("图谱节点来源资料标识不是有效 JSON", exception);
        }
    }

    /**
     * 将来源资料标识列表转换为 JSON 数组。
     *
     * @param value 来源资料标识列表
     * @return 来源资料标识 JSON
     */
    @Named("longListToJson")
    public String longListToJson(List<Long> value) {
        try {
            // 使用项目统一 ObjectMapper 序列化来源资料标识
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化图谱节点来源资料标识", exception);
        }
    }
}

package com.flevin.knowgraph.server.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.io.IOException;
import java.util.List;

/**
 * 为浏览器 API 建立独立 JSON 编码器，避免 Snowflake Long 在 JavaScript 中丢失精度。
 */
@Configuration
public class ApiObjectMapperConfiguration {

    /**
     * 创建供持久化、内部结果快照和非 Web 组件使用的默认 JSON 编码器。
     *
     * @param objectMapperBuilder Spring Boot 已配置时间和模块支持的 ObjectMapper 构建器
     * @return 保持 Long 数值语义的默认 ObjectMapper
     */
    @Bean("persistenceObjectMapper")
    @Primary
    public ObjectMapper persistenceObjectMapper(Jackson2ObjectMapperBuilder objectMapperBuilder) {
        // 按 Spring Boot 的默认 JSON 配置创建内部编码器，避免 API 专用规则污染持久化快照
        return objectMapperBuilder.createXmlMapper(false).build();
    }

    /**
     * 复制 Spring 的默认 ObjectMapper，并只在 HTTP/SSE 输出层把 Long 写为十进制字符串。
     *
     * @param objectMapper Spring 默认 ObjectMapper，仍供持久化 JSON 和内部结果快照使用
     * @return 用于浏览器 API 的安全 JSON 编码器
     */
    @Bean("apiObjectMapper")
    public ObjectMapper apiObjectMapper(
            @Qualifier("persistenceObjectMapper") ObjectMapper objectMapper
    ) {
        SimpleModule longIdModule = new SimpleModule();
        // 仅修改命名为 id、*Id、*Ids 的属性，避免时间戳和文件大小等普通 Long 数值误变为字符串
        longIdModule.setSerializerModifier(new LongIdentifierSerializerModifier());

        // 仅复制默认配置，避免影响数据库来源 ID JSON 与内部模型结果快照的数值语义
        return objectMapper.copy().registerModule(longIdModule);
    }

    /**
     * 仅将 API 响应中的 Long 标识字段改写为字符串，避免浏览器将 Snowflake 标识解析为不安全的 number。
     */
    private static final class LongIdentifierSerializerModifier extends BeanSerializerModifier {

        /**
         * 根据公开属性名调整 Long 标识字段的 JSON 序列化器。
         *
         * @param config 当前 Jackson 序列化配置
         * @param beanDesc 当前 Bean 描述
         * @param beanProperties 当前 Bean 的属性写入器
         * @return 调整后的属性写入器
         */
        @Override
        public List<BeanPropertyWriter> changeProperties(
                SerializationConfig config,
                BeanDescription beanDesc,
                List<BeanPropertyWriter> beanProperties
        ) {
            // 仅为单值 Long 标识附加十进制字符串序列化器，保持普通数值字段的 JSON 类型不变
            beanProperties.stream()
                    .filter(property -> isLongIdentifierProperty(property.getName(), property.getType().getRawClass()))
                    .forEach(property -> property.assignSerializer(ToStringSerializer.instance));

            // 批量接口中的 Long 标识列表也必须按字符串传输，避免前端数组元素出现精度丢失
            beanProperties.stream()
                    .filter(this::isLongIdentifierCollectionProperty)
                    .forEach(property -> property.assignSerializer(LongIdentifierCollectionSerializer.INSTANCE));
            return beanProperties;
        }

        /**
         * 判断当前属性是否为需要跨越 JavaScript 安全整数边界的 Long 标识。
         *
         * @param propertyName JSON 属性名
         * @param propertyType 属性运行时类型
         * @return 是 Long 标识字段时返回 true
         */
        private boolean isLongIdentifierProperty(String propertyName, Class<?> propertyType) {
            return (propertyType == Long.class || propertyType == Long.TYPE)
                    && ("id".equals(propertyName) || propertyName.endsWith("Id"));
        }

        /**
         * 判断当前属性是否为 Long 标识列表。
         *
         * @param property 当前 Bean 属性写入器
         * @return 是 Long 标识列表时返回 true
         */
        private boolean isLongIdentifierCollectionProperty(BeanPropertyWriter property) {
            return property.getType().isCollectionLikeType()
                    && property.getType().getContentType() != null
                    && property.getType().getContentType().getRawClass() == Long.class
                    && property.getName().endsWith("Ids");
        }
    }

    /**
     * 将批量响应中的 Long 标识数组写为字符串数组，保证每个 Snowflake 标识都可由浏览器无损读取。
     */
    private static final class LongIdentifierCollectionSerializer extends JsonSerializer<Object> {

        private static final LongIdentifierCollectionSerializer INSTANCE = new LongIdentifierCollectionSerializer();

        /**
         * 序列化 Long 标识列表。
         *
         * @param value 当前属性值
         * @param generator JSON 输出器
         * @param provider Jackson 序列化上下文
         * @throws IOException 输出失败时抛出
         */
        @Override
        public void serialize(
                Object value,
                JsonGenerator generator,
                SerializerProvider provider
        ) throws IOException {
            generator.writeStartArray();
            for (Object item : (Iterable<?>) value) {
                if (item == null) {
                    generator.writeNull();
                    continue;
                }
                generator.writeString(item.toString());
            }
            generator.writeEndArray();
        }
    }
}

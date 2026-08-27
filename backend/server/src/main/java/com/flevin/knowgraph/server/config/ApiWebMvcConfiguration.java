package com.flevin.knowgraph.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * 将浏览器 HTTP JSON 响应切换为 Long 安全编码器，保持请求体对字符串 Long 的兼容解析。
 */
@Configuration
public class ApiWebMvcConfiguration implements WebMvcConfigurer {

    private final ObjectMapper apiObjectMapper;

    public ApiWebMvcConfiguration(
            @Qualifier("apiObjectMapper") ObjectMapper apiObjectMapper
    ) {
        this.apiObjectMapper = apiObjectMapper;
    }

    /**
     * 为 Spring MVC 的 JSON 转换器注入 API 专用 ObjectMapper。
     *
     * @param converters Spring 已注册的消息转换器
     */
    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.stream()
                .filter(MappingJackson2HttpMessageConverter.class::isInstance)
                .map(MappingJackson2HttpMessageConverter.class::cast)
                .forEach(converter -> converter.setObjectMapper(apiObjectMapper));
    }
}

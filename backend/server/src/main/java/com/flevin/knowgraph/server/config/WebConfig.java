package com.flevin.knowgraph.server.config;

import com.flevin.knowgraph.server.config.properties.AppStorageProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 接口配置，允许独立运行的前端工作台访问本地后端。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AppStorageProperties storageProperties;

    public WebConfig(AppStorageProperties storageProperties) {
        this.storageProperties = storageProperties;
    }

    /**
     * 配置本地前端访问后端 API 所需的跨域规则。
     *
     * @param registry Spring MVC 跨域规则注册器
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 仅允许配置中的前端来源访问当前 API，并限制为实际使用的方法
        registry.addMapping("/**")
                .allowedOrigins(storageProperties.getFrontendOrigin())
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("X-Trace-Id");
    }
}

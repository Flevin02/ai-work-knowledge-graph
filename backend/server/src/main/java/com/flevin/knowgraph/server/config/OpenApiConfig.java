package com.flevin.knowgraph.server.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

/**
 * Knife4j/OpenAPI 文档基础信息配置。
 */
@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "知脉 AI 工作知识图谱维护助手 API",
                version = "v0.1",
                description = "提供办公资料导入、知识图谱查询、关系审核和知识健康检查能力。"
        )
)
public class OpenApiConfig {
}

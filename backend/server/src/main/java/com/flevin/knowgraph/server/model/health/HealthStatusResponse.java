package com.flevin.knowgraph.server.model.health;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

/**
 * 后端健康状态响应。
 *
 * @param status 服务状态
 * @param service 服务名称
 * @param time 响应时间
 */
@Schema(name = "HealthStatusResponse", description = "Java 后端健康状态")
public record HealthStatusResponse(
        @Schema(description = "服务运行状态", example = "ok")
        String status,
        @Schema(description = "服务名称", example = "ai-work-knowledge-graph-backend")
        String service,
        @Schema(description = "服务端响应时间", example = "2026-08-17T16:55:26.812425+08:00")
        OffsetDateTime time
) {
}

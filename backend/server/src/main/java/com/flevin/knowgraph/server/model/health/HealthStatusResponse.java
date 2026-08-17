package com.flevin.knowgraph.server.model.health;

import java.time.OffsetDateTime;

/**
 * 后端健康状态响应。
 *
 * @param status 服务状态
 * @param service 服务名称
 * @param time 响应时间
 */
public record HealthStatusResponse(
        String status,
        String service,
        OffsetDateTime time
) {
}

package com.flevin.knowgraph.server.service.health;

import com.flevin.knowgraph.server.model.health.HealthStatusResponse;

/**
 * 后端健康状态服务。
 */
public interface HealthService {

    /**
     * 获取当前后端服务的运行状态。
     *
     * @return 后端健康状态
     */
    HealthStatusResponse getStatus();
}

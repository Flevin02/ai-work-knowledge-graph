package com.flevin.knowgraph.server.service.health.impl;

import com.flevin.knowgraph.server.model.health.HealthStatusResponse;
import com.flevin.knowgraph.server.service.health.HealthService;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

/**
 * 后端健康状态服务实现。
 */
@Service
public class HealthServiceImpl implements HealthService {

    /**
     * 获取当前后端服务的运行状态。
     *
     * @return 后端健康状态
     */
    @Override
    public HealthStatusResponse getStatus() {
        return new HealthStatusResponse(
                "ok",
                "ai-work-knowledge-graph-backend",
                OffsetDateTime.now()
        );
    }
}

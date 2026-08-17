package com.flevin.knowgraph.server.controller.health;

import com.flevin.knowgraph.common.model.ApiResponse;
import com.flevin.knowgraph.server.model.health.HealthStatusResponse;
import com.flevin.knowgraph.server.service.health.HealthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/health")
public class HealthController {

    private final HealthService healthService;

    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    @GetMapping(value = "", name = "查询后端健康状态")
    public ApiResponse<HealthStatusResponse> health() {
        // 获取当前 Java 后端运行状态
        HealthStatusResponse response = healthService.getStatus();

        // 使用脚手架统一响应结构返回健康信息
        return ApiResponse.success(response);
    }
}

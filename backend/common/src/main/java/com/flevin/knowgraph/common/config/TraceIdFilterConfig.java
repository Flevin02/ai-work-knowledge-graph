package com.flevin.knowgraph.common.config;

import com.flevin.knowgraph.common.filter.TraceIdFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * TraceId 过滤器注册配置。
 */
@Configuration
public class TraceIdFilterConfig {

    /**
     * 注册最高优先级的 TraceId 过滤器。
     *
     * @return TraceId 过滤器注册对象
     */
    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilter() {
        FilterRegistrationBean<TraceIdFilter> registrationBean = new FilterRegistrationBean<>();

        // 注册链路追踪过滤器
        registrationBean.setFilter(new TraceIdFilter());

        // 拦截当前服务的全部请求
        registrationBean.addUrlPatterns("/*");

        // 保证在其他过滤器之前建立链路上下文
        registrationBean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registrationBean;
    }
}

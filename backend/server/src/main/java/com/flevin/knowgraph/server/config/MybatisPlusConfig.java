package com.flevin.knowgraph.server.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件配置，统一管理 SQLite 服务端分页边界。
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 创建带 SQLite 方言和单页上限的 MyBatis-Plus 拦截器。
     *
     * @return MyBatis-Plus 插件拦截器
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor paginationInterceptor = new PaginationInnerInterceptor(DbType.SQLITE);

        // 限制分页插件单次最多返回 100 条资料，防止接口退化为全量读取
        paginationInterceptor.setMaxLimit(100L);

        // 注册 SQLite 分页插件，由插件统一生成分页和总数查询
        interceptor.addInnerInterceptor(paginationInterceptor);
        return interceptor;
    }
}

package com.flevin.knowgraph.server.config;

import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * SQLite 表结构初始化器，应用启动时以幂等方式创建当前阶段所需数据表。
 */
@Component
public class DatabaseSchemaInitializer {

    private final DataSource dataSource;

    public DatabaseSchemaInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 执行来源资料和导入批次表初始化脚本。
     */
    @PostConstruct
    public void initialize() {
        // 加载当前阶段的 SQLite 表结构脚本
        ClassPathResource schemaResource = new ClassPathResource("db/schema.sql");

        // 构建 Spring JDBC 脚本执行器
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(schemaResource);

        // 执行幂等建表脚本，确保 Repository 使用前表结构已就绪
        populator.execute(dataSource);
    }
}

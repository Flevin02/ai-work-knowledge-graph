package com.flevin.knowgraph.server.config;

import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * SQLite 表结构初始化器，应用启动时以幂等方式创建当前阶段所需数据表。
 */
@Component
public class DatabaseSchemaInitializer {

    private static final String DEFAULT_SPACE_ID = "default-space";

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    public DatabaseSchemaInitializer(
            DataSource dataSource,
            JdbcTemplate jdbcTemplate
    ) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
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

        // 为旧版本数据库补充导入批次所属知识空间字段
        addColumnIfMissing(
                "import_batches",
                "space_id",
                "ALTER TABLE import_batches ADD COLUMN space_id TEXT REFERENCES knowledge_spaces(id)"
        );

        // 为旧版本数据库补充来源资料所属知识空间字段
        addColumnIfMissing(
                "source_documents",
                "space_id",
                "ALTER TABLE source_documents ADD COLUMN space_id TEXT REFERENCES knowledge_spaces(id)"
        );

        // 将升级前已有批次归入默认知识空间
        jdbcTemplate.update(
                "UPDATE import_batches SET space_id = ? WHERE space_id IS NULL OR space_id = ''",
                DEFAULT_SPACE_ID
        );

        // 将升级前已有来源资料归入默认知识空间
        jdbcTemplate.update(
                "UPDATE source_documents SET space_id = ? WHERE space_id IS NULL OR space_id = ''",
                DEFAULT_SPACE_ID
        );

        // 移除旧版本的全局内容指纹唯一索引，改为知识空间内唯一
        jdbcTemplate.execute("DROP INDEX IF EXISTS uk_source_documents_content_hash");

        // 为升级后的来源资料建立空间内内容指纹唯一索引
        jdbcTemplate.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS uk_source_documents_space_hash
                ON source_documents(space_id, content_hash)
                """);

        // 为升级后的来源资料建立知识空间和导入时间查询索引
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_source_documents_space_imported_at
                ON source_documents(space_id, imported_at DESC)
                """);

        // 为升级后的导入批次建立知识空间和创建时间查询索引
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_import_batches_space_created_at
                ON import_batches(space_id, created_at DESC)
                """);
    }

    /**
     * 为旧版本 SQLite 表补充缺失字段，已有字段保持不变。
     *
     * @param tableName 表名
     * @param columnName 字段名
     * @param alterSql 新增字段 SQL
     */
    private void addColumnIfMissing(
            String tableName,
            String columnName,
            String alterSql
    ) {
        // 查询 SQLite 表结构，确认旧数据库是否缺少目标字段
        boolean columnExists = jdbcTemplate.queryForList("PRAGMA table_info(" + tableName + ")")
                .stream()
                .anyMatch(column -> columnName.equals(column.get("name")));
        if (columnExists) {
            return;
        }

        // 仅在字段缺失时执行兼容性迁移
        jdbcTemplate.execute(alterSql);
    }
}

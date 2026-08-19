package com.flevin.knowgraph.server.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SQLite 数据源集成测试，验证连接池上限、连接级 PRAGMA 和单写锁等待边界。
 */
@SpringBootTest(properties = {
        "app.database-path=target/test-data/database-config.sqlite",
        "app.upload-dir=target/test-data/database-config-uploads",
        "spring.datasource.hikari.data-source-properties[busy_timeout]=200",
        "spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.minimum-idle=1",
        "spring.datasource.hikari.connection-timeout=250",
        "spring.datasource.hikari.initialization-fail-timeout=250",
        "ai.enabled=false"
})
class SqliteDataSourceIntegrationTests {

    @Autowired
    private DataSource dataSource;

    @Test
    void bindsBoundedPoolAndAppliesPragmasToEveryConnection() throws Exception {
        // 确认 Spring Boot 自动配置实际选择了默认 HikariCP 实现
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        HikariDataSource hikariDataSource = (HikariDataSource) dataSource;

        // 验证 Spring Boot 原生 Hikari 参数已绑定到自动创建的数据源
        assertThat(hikariDataSource.getPoolName()).isEqualTo("knowledge-graph-sqlite");
        assertThat(hikariDataSource.getMaximumPoolSize()).isEqualTo(2);
        assertThat(hikariDataSource.getMinimumIdle()).isEqualTo(1);
        assertThat(hikariDataSource.getConnectionTimeout()).isEqualTo(250L);

        // 同时借出池上限数量的连接，验证每条物理连接都应用 SQLite 约束
        try (Connection firstConnection = dataSource.getConnection();
             Connection secondConnection = dataSource.getConnection()) {
            // 检查第一条连接的 WAL、外键和忙等待参数
            assertSqlitePragmas(firstConnection);

            // 检查第二条连接，防止只在数据库初始化连接上偶然生效
            assertSqlitePragmas(secondConnection);

            // 池已达到上限时，额外请求必须在有界时间后携带池名失败
            assertThatThrownBy(dataSource::getConnection)
                    .isInstanceOf(SQLTransientConnectionException.class)
                    .hasMessageContaining("knowledge-graph-sqlite")
                    .hasMessageContaining("Connection is not available");
        }
    }

    @Test
    void walAllowsReadingWhileSecondWriterFailsAfterBusyTimeout() throws Exception {
        // 分别借出写事务连接和竞争连接，模拟导入或抽取同时访问 SQLite
        try (Connection writerConnection = dataSource.getConnection();
             Connection contenderConnection = dataSource.getConnection()) {
            // 准备独立探针表并清理上次数据，避免修改任何业务表
            executeUpdate(writerConnection, "CREATE TABLE IF NOT EXISTS database_lock_probe (id INTEGER PRIMARY KEY, value TEXT)");
            executeUpdate(writerConnection, "DELETE FROM database_lock_probe");

            // 开启显式写事务并保持未提交状态，占用 SQLite 唯一写锁
            writerConnection.setAutoCommit(false);
            executeUpdate(writerConnection, "INSERT INTO database_lock_probe (id, value) VALUES (1, 'writer')");

            // WAL 模式下并发读取应看到已提交快照，不被未提交写事务阻塞
            assertThat(queryScalar(contenderConnection, "SELECT COUNT(*) FROM database_lock_probe"))
                    .isEqualTo("0");

            // 第二个写请求只等待 busy_timeout，随后返回可定位的 SQLITE_BUSY
            assertThatThrownBy(() -> executeUpdate(
                    contenderConnection,
                    "INSERT INTO database_lock_probe (id, value) VALUES (2, 'contender')"
            ))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("SQLITE_BUSY");

            // 回滚测试写事务，释放锁且不保留探针数据
            writerConnection.rollback();
        }
    }

    /**
     * 验证一条池连接实际启用了当前 SQLite 并发和完整性参数。
     *
     * @param connection 待验证连接
     * @throws SQLException PRAGMA 查询失败
     */
    private void assertSqlitePragmas(Connection connection) throws SQLException {
        // WAL 是数据库级持久设置，允许写事务期间读取已提交快照
        assertThat(queryScalar(connection, "PRAGMA journal_mode")).isEqualToIgnoringCase("wal");

        // 外键和忙等待是连接级设置，必须在池创建的每条连接上生效
        assertThat(queryScalar(connection, "PRAGMA foreign_keys")).isEqualTo("1");
        assertThat(queryScalar(connection, "PRAGMA busy_timeout")).isEqualTo("200");
    }

    /**
     * 查询首行首列的标量文本值。
     *
     * @param connection 当前数据库连接
     * @param sql 受测试代码控制的查询语句
     * @return 首行首列文本值
     * @throws SQLException 查询失败
     */
    private String queryScalar(
            Connection connection,
            String sql
    ) throws SQLException {
        // 使用短生命周期语句读取当前连接或 WAL 快照中的标量值
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.getString(1);
        }
    }

    /**
     * 使用指定连接执行更新语句。
     *
     * @param connection 当前数据库连接
     * @param sql 受测试代码控制的更新语句
     * @return 受影响行数
     * @throws SQLException 更新失败
     */
    private int executeUpdate(
            Connection connection,
            String sql
    ) throws SQLException {
        // 使用短生命周期语句执行锁等待测试更新
        try (Statement statement = connection.createStatement()) {
            return statement.executeUpdate(sql);
        }
    }
}

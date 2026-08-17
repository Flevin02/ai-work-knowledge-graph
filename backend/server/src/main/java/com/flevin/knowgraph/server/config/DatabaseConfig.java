package com.flevin.knowgraph.server.config;

import com.flevin.knowgraph.server.config.properties.AppStorageProperties;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * SQLite 数据源配置，负责在建立连接前准备本地数据目录。
 */
@Configuration
@EnableConfigurationProperties(AppStorageProperties.class)
public class DatabaseConfig {

    /**
     * 创建启用外键约束和忙等待的 SQLite 数据源。
     *
     * @param storageProperties 应用本地存储配置
     * @return SQLite 数据源
     */
    @Bean
    public DataSource dataSource(AppStorageProperties storageProperties) {
        // 解析并规范化数据库文件路径，避免运行目录差异导致路径含义不清
        Path databasePath = Path.of(storageProperties.getDatabasePath()).toAbsolutePath().normalize();

        // 创建数据库父目录，确保 SQLite 首次连接时可以生成数据库文件
        createDirectory(databasePath.getParent(), "SQLite 数据目录");

        // 解析并规范化来源资料保存目录
        Path uploadDirectory = Path.of(storageProperties.getUploadDir()).toAbsolutePath().normalize();

        // 创建来源资料保存目录，为后续原始文件持久化做好准备
        createDirectory(uploadDirectory, "来源资料上传目录");

        SQLiteConfig sqliteConfig = new SQLiteConfig();

        // 开启 SQLite 外键约束，保证批次与来源资料的关联完整
        sqliteConfig.enforceForeignKeys(true);

        // 设置短暂忙等待，降低本地并发写入时立即报锁冲突的概率
        sqliteConfig.setBusyTimeout(5000);

        SQLiteDataSource dataSource = new SQLiteDataSource(sqliteConfig);

        // 使用规范化后的本地文件路径建立 SQLite 连接
        dataSource.setUrl("jdbc:sqlite:" + databasePath);
        return dataSource;
    }

    /**
     * 创建本地存储目录，并在失败时保留明确的目录上下文。
     *
     * @param directory 待创建目录
     * @param description 目录业务用途
     */
    private void createDirectory(
            Path directory,
            String description
    ) {
        try {
            // 递归创建目录；目录已存在时保持不变
            Files.createDirectories(directory);
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建" + description + ": " + directory, exception);
        }
    }
}

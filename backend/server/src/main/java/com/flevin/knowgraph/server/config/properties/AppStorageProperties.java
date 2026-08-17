package com.flevin.knowgraph.server.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 应用本地存储配置，统一管理 SQLite 数据库和来源资料上传目录。
 */
@Data
@ConfigurationProperties(prefix = "app")
public class AppStorageProperties {

    /**
     * SQLite 数据库文件路径。
     */
    private String databasePath;

    /**
     * 来源资料原始文件保存目录。
     */
    private String uploadDir;

    /**
     * 允许访问后端接口的前端来源地址。
     */
    private String frontendOrigin;
}

package com.flevin.knowgraph.server.storage;

import com.flevin.knowgraph.server.config.properties.AppStorageProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 本地运行目录初始化器，在数据源首次连接和文件导入前准备来源资料上传目录。
 */
@Component("localStorageInitializer")
@RequiredArgsConstructor
public class LocalStorageInitializer {

    private final AppStorageProperties storageProperties;

    /**
     * 创建来源资料上传目录。
     */
    @PostConstruct
    public void initialize() {
        // 解析并规范化来源资料保存目录
        Path uploadDirectory = Path.of(storageProperties.getUploadDir()).toAbsolutePath().normalize();

        // 创建来源资料保存目录，为后续原始文件持久化做好准备
        createDirectory(uploadDirectory, "来源资料上传目录");
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

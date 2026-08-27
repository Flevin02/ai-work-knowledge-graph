package com.flevin.knowgraph.server.storage;

import com.flevin.knowgraph.server.config.properties.AppStorageProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * 本地文件存储组件，统一维护知识空间隔离目录和来源资料原始文件。
 */
@Slf4j
@Component
public class LocalFileStorage {

    private final AppStorageProperties storageProperties;

    public LocalFileStorage(AppStorageProperties storageProperties) {
        this.storageProperties = storageProperties;
    }

    /**
     * 准备指定知识空间的来源资料目录。
     *
     * @param spaceId 知识空间标识
     * @return 规范化后的空间文档目录
     * @throws IOException 目录创建失败时抛出
     */
    public Path prepareDocumentsDirectory(Long spaceId) throws IOException {
        // 解析并规范化全局上传根目录
        Path uploadRoot = Path.of(storageProperties.getUploadDir()).toAbsolutePath().normalize();

        // 在知识空间目录下使用 documents 子目录保存原始事实来源
        Path documentsDirectory = uploadRoot.resolve(String.valueOf(spaceId)).resolve("documents").normalize();
        if (!documentsDirectory.startsWith(uploadRoot)) {
            throw new IOException("知识空间目录超出配置的上传根目录");
        }

        // 递归创建知识空间来源资料目录
        Files.createDirectories(documentsDirectory);
        return documentsDirectory;
    }

    /**
     * 将一份来源资料原始字节写入对应知识空间目录。
     *
     * @param spaceId 知识空间标识
     * @param extension 已校验的文件扩展名
     * @param contentBytes 原始文件字节
     * @return 已保存文件的规范化路径
     * @throws IOException 目录准备或文件写入失败时抛出
     */
    public Path storeSourceDocument(
            Long spaceId,
            String extension,
            byte[] contentBytes
    ) throws IOException {
        // 准备当前知识空间的独立来源资料目录
        Path documentsDirectory = prepareDocumentsDirectory(spaceId);

        // 使用服务端 UUID 文件名，避免覆盖文件或使用客户端名称拼接路径
        Path targetFile = documentsDirectory.resolve(UUID.randomUUID() + "." + extension).normalize();

        try {
            // 以新建模式写入原始事实源，拒绝覆盖已有文件
            Files.write(targetFile, contentBytes, StandardOpenOption.CREATE_NEW);
            return targetFile;
        } catch (IOException exception) {
            // 写入中断时清理可能产生的不完整文件
            Files.deleteIfExists(targetFile);
            throw exception;
        }
    }

    /**
     * 删除尚未形成数据库记录的本次新文件。
     *
     * @param storedFile 待清理文件路径
     */
    public void deleteOrphanFile(Path storedFile) {
        try {
            // 仅删除调用方明确传入的孤儿文件，不递归处理知识空间目录
            Files.deleteIfExists(storedFile);
        } catch (IOException exception) {
            log.warn("无法清理来源资料孤儿文件: path={}", storedFile, exception);
        }
    }
}

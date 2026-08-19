package com.flevin.knowgraph.server.repository.document;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flevin.knowgraph.server.model.document.SourceDocument;
import com.flevin.knowgraph.server.model.document.SourceDocumentPage;
import com.flevin.knowgraph.server.model.document.SourceDocumentType;
import com.flevin.knowgraph.server.repository.entity.SourceDocumentEntity;
import com.flevin.knowgraph.server.repository.mapper.SourceDocumentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 来源资料数据访问对象，使用 MyBatis-Plus 简化保存、去重、列表和原文查询。
 */
@Repository
@RequiredArgsConstructor
public class SourceDocumentRepository {

    private final SourceDocumentMapper sourceDocumentMapper;

    /**
     * 保存已完成解析和原始文件落盘的来源资料。
     *
     * @param document 来源资料模型
     */
    public void save(SourceDocument document) {
        // 将领域来源资料转换为 MyBatis-Plus 持久化实体
        SourceDocumentEntity entity = toEntity(document);

        // 使用 BaseMapper 插入来源资料结构化索引和原文
        sourceDocumentMapper.insert(entity);
    }

    /**
     * 按 SHA-256 内容指纹查询已导入的来源资料。
     *
     * @param spaceId 知识空间标识
     * @param contentHash SHA-256 内容指纹
     * @return 已存在的来源资料；不存在时返回空
     */
    public Optional<SourceDocument> findByContentHash(
            String spaceId,
            String contentHash
    ) {
        // 使用知识空间和内容指纹查询空间内重复来源资料
        SourceDocumentEntity entity = sourceDocumentMapper.selectOne(
                Wrappers.<SourceDocumentEntity>lambdaQuery()
                        .eq(SourceDocumentEntity::getSpaceId, spaceId)
                        .eq(SourceDocumentEntity::getContentHash, contentHash)
        );
        return Optional.ofNullable(entity).map(this::toDomain);
    }

    /**
     * 按知识空间和资料标识查询完整来源资料，供原文预览和后续 AI 处理使用。
     *
     * @param spaceId 知识空间标识
     * @param documentId 来源资料标识
     * @return 完整来源资料；不存在时返回空
     */
    public Optional<SourceDocument> findById(
            String spaceId,
            String documentId
    ) {
        // 使用知识空间和资料标识查询完整原文，防止跨空间读取
        SourceDocumentEntity entity = sourceDocumentMapper.selectOne(
                Wrappers.<SourceDocumentEntity>lambdaQuery()
                        .eq(SourceDocumentEntity::getSpaceId, spaceId)
                        .eq(SourceDocumentEntity::getId, documentId)
                        .eq(SourceDocumentEntity::getStatus, "active")
        );
        return Optional.ofNullable(entity).map(this::toDomain);
    }

    /**
     * 查询全部有效来源资料，按首次导入时间倒序返回。
     *
     * @param spaceId 知识空间标识
     * @return 来源资料列表
     */
    public List<SourceDocument> findAll(String spaceId) {
        // 按空间、导入时间和主键查询来源资料，保持现有 API 顺序稳定
        List<SourceDocumentEntity> entities = sourceDocumentMapper.selectList(
                Wrappers.<SourceDocumentEntity>lambdaQuery()
                        .eq(SourceDocumentEntity::getSpaceId, spaceId)
                        .eq(SourceDocumentEntity::getStatus, "active")
                        .orderByDesc(SourceDocumentEntity::getImportedAt)
                        .orderByDesc(SourceDocumentEntity::getId)
        );

        // 将 ORM 实体转换为领域模型，避免 Service 感知 MyBatis-Plus
        return entities.stream().map(this::toDomain).toList();
    }

    /**
     * 使用 MyBatis-Plus 分页插件查询有效来源资料。
     *
     * @param spaceId 知识空间标识
     * @param name 按原始文件名模糊查询；为空时不增加名称条件
     * @param page 页码，从 1 开始
     * @param pageSize 每页数量
     * @return 当前页来源资料和分页元数据
     */
    public SourceDocumentPage findPage(
            String spaceId,
            String name,
            int page,
            int pageSize
    ) {
        Page<SourceDocumentEntity> pageRequest = new Page<>(page, pageSize);

        // 规范化名称筛选条件，空白输入按未筛选处理
        String normalizedName = name == null ? null : name.trim();

        // 组装当前空间和名称条件，避免将用户输入拼接进 SQL
        LambdaQueryWrapper<SourceDocumentEntity> queryWrapper = Wrappers.lambdaQuery();
        queryWrapper.eq(SourceDocumentEntity::getSpaceId, spaceId)
                .eq(SourceDocumentEntity::getStatus, "active");
        if (normalizedName != null && !normalizedName.isEmpty()) {
            // 转义 LIKE 元字符，确保搜索百分号和下划线时按文件名字符匹配
            queryWrapper.apply(
                    "name LIKE {0} ESCAPE '\\'",
                    "%" + escapeLikePattern(normalizedName) + "%"
            );
        }
        queryWrapper.orderByDesc(SourceDocumentEntity::getUpdatedAt)
                .orderByDesc(SourceDocumentEntity::getId);

        // 通过分页插件执行当前空间内的名称模糊查询，并保持更新时间、主键倒序稳定
        Page<SourceDocumentEntity> entityPage = sourceDocumentMapper.selectPage(
                pageRequest,
                queryWrapper
        );

        // 将 ORM 分页记录转换为领域模型，避免 Service 感知 MyBatis-Plus
        List<SourceDocument> documents = entityPage.getRecords().stream()
                .map(this::toDomain)
                .toList();

        // 返回领域分页结果，并保留插件计算的总数和总页数
        return new SourceDocumentPage(
                documents,
                Math.toIntExact(entityPage.getCurrent()),
                Math.toIntExact(entityPage.getSize()),
                entityPage.getTotal(),
                entityPage.getPages()
        );
    }

    /**
     * 转义 SQLite LIKE 查询中的通配符，保留用户输入的文件名语义。
     *
     * @param value 原始名称筛选条件
     * @return 可作为 LIKE 参数使用的转义文本
     */
    private String escapeLikePattern(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    /**
     * 软删除指定来源资料，保留原始文件、数据库记录和历史证据。
     *
     * @param spaceId 知识空间标识
     * @param documentId 来源资料标识
     * @param updatedAt 删除时间
     * @return 实际更新记录数
     */
    public int softDelete(
            String spaceId,
            String documentId,
            Instant updatedAt
    ) {
        SourceDocumentEntity updateEntity = new SourceDocumentEntity();
        updateEntity.setStatus("deleted");
        updateEntity.setUpdatedAt(updatedAt.toString());

        // 只删除当前仍处于 active 状态的来源资料，保持操作幂等
        LambdaUpdateWrapper<SourceDocumentEntity> updateWrapper = Wrappers.lambdaUpdate();
        updateWrapper.eq(SourceDocumentEntity::getSpaceId, spaceId)
                .eq(SourceDocumentEntity::getId, documentId)
                .eq(SourceDocumentEntity::getStatus, "active");

        // 使用 MyBatis-Plus 更新来源资料状态
        return sourceDocumentMapper.update(updateEntity, updateWrapper);
    }

    /**
     * 恢复此前软删除的相同来源资料，不重复写入原始文件。
     *
     * @param spaceId 知识空间标识
     * @param documentId 来源资料标识
     * @param documentType 重新导入时选择的文档业务类型
     * @param updatedAt 恢复时间
     * @return 实际更新记录数
     */
    public int restore(
            String spaceId,
            String documentId,
            SourceDocumentType documentType,
            Instant updatedAt
    ) {
        SourceDocumentEntity updateEntity = new SourceDocumentEntity();
        updateEntity.setStatus("active");
        updateEntity.setDocumentType(documentType.getValue());
        updateEntity.setUpdatedAt(updatedAt.toString());

        // 只恢复当前处于 deleted 状态的同空间来源资料
        LambdaUpdateWrapper<SourceDocumentEntity> updateWrapper = Wrappers.lambdaUpdate();
        updateWrapper.eq(SourceDocumentEntity::getSpaceId, spaceId)
                .eq(SourceDocumentEntity::getId, documentId)
                .eq(SourceDocumentEntity::getStatus, "deleted");

        // 使用 MyBatis-Plus 恢复来源资料状态和业务类型
        return sourceDocumentMapper.update(updateEntity, updateWrapper);
    }

    /**
     * 将 MyBatis-Plus 实体转换为来源资料领域模型。
     *
     * @param entity 持久化实体
     * @return 来源资料领域模型
     */
    private SourceDocument toDomain(SourceDocumentEntity entity) {
        return new SourceDocument(
                entity.getId(),
                entity.getSpaceId(),
                entity.getBatchId(),
                entity.getName(),
                entity.getKind(),
                SourceDocumentType.fromValue(entity.getDocumentType())
                        .orElse(SourceDocumentType.GENERAL),
                entity.getContentHash(),
                entity.getStoragePath(),
                entity.getContentText(),
                entity.getExcerpt(),
                entity.getStatus(),
                entity.getFileSize(),
                Instant.parse(entity.getImportedAt()),
                Instant.parse(entity.getUpdatedAt())
        );
    }

    /**
     * 将来源资料领域模型转换为 MyBatis-Plus 实体。
     *
     * @param document 来源资料领域模型
     * @return 持久化实体
     */
    private SourceDocumentEntity toEntity(SourceDocument document) {
        SourceDocumentEntity entity = new SourceDocumentEntity();
        entity.setId(document.id());
        entity.setSpaceId(document.spaceId());
        entity.setBatchId(document.batchId());
        entity.setName(document.name());
        entity.setKind(document.kind());
        entity.setDocumentType(document.documentType().getValue());
        entity.setContentHash(document.contentHash());
        entity.setStoragePath(document.storagePath());
        entity.setContentText(document.contentText());
        entity.setExcerpt(document.excerpt());
        entity.setStatus(document.status());
        entity.setFileSize(document.fileSize());
        entity.setImportedAt(document.importedAt().toString());
        entity.setUpdatedAt(document.updatedAt().toString());
        return entity;
    }
}

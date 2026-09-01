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
import com.flevin.knowgraph.server.repository.mapping.SourceDocumentEntityMapper;
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
    private final SourceDocumentEntityMapper entityMapper;

    /**
     * 保存已完成解析和原始文件落盘的来源资料。
     *
     * @param document 来源资料模型
     */
    public void save(SourceDocument document) {
        // 将领域来源资料转换为 MyBatis-Plus 持久化实体
        SourceDocumentEntity entity = entityMapper.toEntity(document);

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
            Long spaceId,
            String contentHash
    ) {
        // 使用知识空间和内容指纹查询空间内重复来源资料
        SourceDocumentEntity entity = sourceDocumentMapper.selectOne(
                Wrappers.<SourceDocumentEntity>lambdaQuery()
                        .eq(SourceDocumentEntity::getSpaceId, spaceId)
                        .eq(SourceDocumentEntity::getContentHash, contentHash)
        );
        return Optional.ofNullable(entity).map(entityMapper::toDomain);
    }

    /**
     * 按知识空间和资料标识查询完整来源资料，供原文预览和后续 AI 处理使用。
     *
     * @param spaceId 知识空间标识
     * @param documentId 来源资料标识
     * @return 完整来源资料；不存在时返回空
     */
    public Optional<SourceDocument> findById(
            Long spaceId,
            Long documentId
    ) {
        // 使用知识空间和资料标识查询完整原文，防止跨空间读取
        SourceDocumentEntity entity = sourceDocumentMapper.selectOne(
                Wrappers.<SourceDocumentEntity>lambdaQuery()
                        .eq(SourceDocumentEntity::getSpaceId, spaceId)
                        .eq(SourceDocumentEntity::getId, documentId)
                        .eq(SourceDocumentEntity::getStatus, "active")
        );
        return Optional.ofNullable(entity).map(entityMapper::toDomain);
    }

    /**
     * 查询全部有效来源资料，按首次导入时间倒序返回。
     *
     * @param spaceId 知识空间标识
     * @return 来源资料列表
     */
    public List<SourceDocument> findAll(Long spaceId) {
        // 按空间、导入时间和主键查询来源资料，保持现有 API 顺序稳定
        List<SourceDocumentEntity> entities = sourceDocumentMapper.selectList(
                Wrappers.<SourceDocumentEntity>lambdaQuery()
                        .eq(SourceDocumentEntity::getSpaceId, spaceId)
                        .eq(SourceDocumentEntity::getStatus, "active")
                        .orderByDesc(SourceDocumentEntity::getImportedAt)
                        .orderByDesc(SourceDocumentEntity::getId)
        );

        // 将 ORM 实体转换为领域模型，避免 Service 感知 MyBatis-Plus
        return entities.stream().map(entityMapper::toDomain).toList();
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
            Long spaceId,
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
                    "name LIKE {0} ESCAPE '\\\\'",
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
                .map(entityMapper::toDomain)
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
     * 转义 MySQL LIKE 查询中的通配符，保留用户输入的文件名语义。
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
            Long spaceId,
            Long documentId,
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
            Long spaceId,
            Long documentId,
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
     * 将来源资料更新为新版本内容，保留标识、名称、业务类型和创建时间。
     *
     * <p>用于增量导入：内容指纹变化后原位替换事实源内容与文件，
     * 历史抽取、标签和关系由调用方按内容哈希冻结失效。</p>
     *
     * @param spaceId 知识空间标识
     * @param documentId 来源资料标识
     * @param kind 新内容的文件格式
     * @param contentHash 新内容 SHA-256 指纹
     * @param storagePath 新的原始文件存储路径
     * @param contentText 新的解析文本
     * @param excerpt 新的导入预览摘要
     * @param fileSize 新文件大小（字节）
     * @param updatedAt 更新时间
     * @return 实际更新记录数
     */
    public int updateVersion(
            Long spaceId,
            Long documentId,
            String kind,
            String contentHash,
            String storagePath,
            String contentText,
            String excerpt,
            long fileSize,
            Instant updatedAt
    ) {
        SourceDocumentEntity updateEntity = new SourceDocumentEntity();
        updateEntity.setKind(kind);
        updateEntity.setContentHash(contentHash);
        updateEntity.setStoragePath(storagePath);
        updateEntity.setContentText(contentText);
        updateEntity.setExcerpt(excerpt);
        updateEntity.setFileSize(fileSize);
        updateEntity.setUpdatedAt(updatedAt.toString());

        // 只更新仍处于 active 状态的同空间来源资料，已删除资料不允许原位更新
        LambdaUpdateWrapper<SourceDocumentEntity> updateWrapper = Wrappers.lambdaUpdate();
        updateWrapper.eq(SourceDocumentEntity::getSpaceId, spaceId)
                .eq(SourceDocumentEntity::getId, documentId)
                .eq(SourceDocumentEntity::getStatus, "active");

        return sourceDocumentMapper.update(updateEntity, updateWrapper);
    }

}

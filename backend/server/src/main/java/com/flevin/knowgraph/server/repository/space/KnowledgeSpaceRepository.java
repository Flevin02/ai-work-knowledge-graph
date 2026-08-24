package com.flevin.knowgraph.server.repository.space;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.flevin.knowgraph.server.model.space.KnowledgeSpace;
import com.flevin.knowgraph.server.repository.entity.KnowledgeSpaceEntity;
import com.flevin.knowgraph.server.repository.mapper.KnowledgeSpaceMapper;
import com.flevin.knowgraph.server.repository.mapping.KnowledgeSpaceEntityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 知识空间数据访问对象，使用 MyBatis-Plus 简化有效空间查询、创建和软删除。
 */
@Repository
@RequiredArgsConstructor
public class KnowledgeSpaceRepository {

    private final KnowledgeSpaceMapper knowledgeSpaceMapper;
    private final KnowledgeSpaceEntityMapper entityMapper;

    /**
     * 查询全部有效知识空间。
     *
     * @return 按创建时间排序的有效知识空间
     */
    public List<KnowledgeSpace> findAllActive() {
        // 使用 MyBatis-Plus Lambda 条件构造器查询未软删除空间
        List<KnowledgeSpaceEntity> entities = knowledgeSpaceMapper.selectList(
                Wrappers.<KnowledgeSpaceEntity>lambdaQuery()
                        .eq(KnowledgeSpaceEntity::getStatus, "active")
                        .orderByAsc(KnowledgeSpaceEntity::getCreatedAt)
                        .orderByAsc(KnowledgeSpaceEntity::getId)
        );

        // 将持久化实体转换为领域 record，阻止 ORM 类型向 Service 层泄漏
        return entities.stream().map(entityMapper::toDomain).toList();
    }

    /**
     * 按标识查询有效知识空间。
     *
     * @param spaceId 知识空间标识
     * @return 有效知识空间；不存在或已删除时返回空
     */
    public Optional<KnowledgeSpace> findActiveById(String spaceId) {
        // 使用空间标识和 active 状态查询单条有效记录
        KnowledgeSpaceEntity entity = knowledgeSpaceMapper.selectOne(
                Wrappers.<KnowledgeSpaceEntity>lambdaQuery()
                        .eq(KnowledgeSpaceEntity::getId, spaceId)
                        .eq(KnowledgeSpaceEntity::getStatus, "active")
        );
        // 将查询实体转换为领域模型，避免 ORM 类型泄漏到 Service 层
        return Optional.ofNullable(entity).map(entityMapper::toDomain);
    }

    /**
     * 判断是否存在同名有效知识空间。
     *
     * @param name 规范化后的知识空间名称
     * @return 存在同名有效空间时返回 true
     */
    public boolean existsActiveByName(String name) {
        // 使用 COUNT 查询同名且有效的知识空间数量
        Long count = knowledgeSpaceMapper.selectCount(
                Wrappers.<KnowledgeSpaceEntity>lambdaQuery()
                        .eq(KnowledgeSpaceEntity::getName, name)
                        .eq(KnowledgeSpaceEntity::getStatus, "active")
        );
        return count != null && count > 0;
    }

    /**
     * 保存新知识空间。
     *
     * @param space 新知识空间模型
     */
    public void save(KnowledgeSpace space) {
        // 将领域模型转换为 MyBatis-Plus 持久化实体
        KnowledgeSpaceEntity entity = entityMapper.toEntity(space);

        // 使用 BaseMapper 插入新知识空间
        knowledgeSpaceMapper.insert(entity);
    }

    /**
     * 将知识空间标记为已删除，不级联删除来源资料或图谱事实。
     *
     * @param spaceId 知识空间标识
     * @param updatedAt 删除操作时间
     * @return 实际更新记录数
     */
    public int softDelete(
            String spaceId,
            Instant updatedAt
    ) {
        KnowledgeSpaceEntity updateEntity = new KnowledgeSpaceEntity();
        updateEntity.setStatus("deleted");
        updateEntity.setUpdatedAt(updatedAt.toString());

        // 只更新当前仍然有效的空间，保持软删除幂等
        LambdaUpdateWrapper<KnowledgeSpaceEntity> updateWrapper = Wrappers.lambdaUpdate();
        updateWrapper.eq(KnowledgeSpaceEntity::getId, spaceId)
                .eq(KnowledgeSpaceEntity::getStatus, "active");

        // 使用 BaseMapper 执行带状态边界的软删除
        return knowledgeSpaceMapper.update(updateEntity, updateWrapper);
    }

}

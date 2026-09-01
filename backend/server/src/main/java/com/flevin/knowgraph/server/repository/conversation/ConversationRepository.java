package com.flevin.knowgraph.server.repository.conversation;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.flevin.knowgraph.server.repository.entity.ConversationEntity;
import com.flevin.knowgraph.server.repository.mapper.ConversationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 只读问答会话数据访问对象，负责会话保存和空间隔离查询。
 */
@Repository
@RequiredArgsConstructor
public class ConversationRepository {

    private final ConversationMapper mapper;

    /**
     * 保存一条问答会话。
     *
     * @param entity 会话持久化实体
     */
    public void save(ConversationEntity entity) {
        // 将会话实体写入 conversations 表；主键由应用侧 Snowflake 生成
        mapper.insert(entity);
    }

    /**
     * 按空间和会话标识查询有效会话，保证跨空间会话不可见。
     *
     * @param spaceId 知识空间标识
     * @param conversationId 会话标识
     * @return 空间内有效会话实体
     */
    public Optional<ConversationEntity> findActiveById(
            Long spaceId,
            Long conversationId
    ) {
        // 限定空间与有效状态，避免跨空间读取他人会话
        return Optional.ofNullable(mapper.selectOne(Wrappers.lambdaQuery(ConversationEntity.class)
                .eq(ConversationEntity::getId, conversationId)
                .eq(ConversationEntity::getSpaceId, spaceId)
                .eq(ConversationEntity::getStatus, "active")));
    }

    /**
     * 刷新会话最近更新时间；新消息提交后调用，保证会话排序语义正确。
     *
     * @param spaceId 知识空间标识
     * @param conversationId 会话标识
     * @param updatedAt 新的更新时间，ISO-8601 UTC 字符串
     */
    public void touch(
            Long spaceId,
            Long conversationId,
            String updatedAt
    ) {
        // 仅更新时间字段，不触碰会话其他事实
        mapper.update(null, Wrappers.lambdaUpdate(ConversationEntity.class)
                .eq(ConversationEntity::getId, conversationId)
                .eq(ConversationEntity::getSpaceId, spaceId)
                .set(ConversationEntity::getUpdatedAt, updatedAt));
    }
}

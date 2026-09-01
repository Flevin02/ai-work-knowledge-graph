package com.flevin.knowgraph.server.repository.conversation;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.flevin.knowgraph.server.repository.entity.ConversationMessageEntity;
import com.flevin.knowgraph.server.repository.entity.MessageCitationEntity;
import com.flevin.knowgraph.server.repository.mapper.ConversationMessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 问答会话消息数据访问对象，负责消息保存和按会话恢复历史。
 */
@Repository
@RequiredArgsConstructor
public class ConversationMessageRepository {

    private final ConversationMessageMapper mapper;
    private final MessageCitationRepository messageCitationRepository;

    /**
     * 保存一条问答消息。
     *
     * @param entity 消息持久化实体
     */
    public void save(ConversationMessageEntity entity) {
        // 将消息实体写入 conversation_messages 表；主键由应用侧 Snowflake 生成
        mapper.insert(entity);
    }

    /**
     * 在同一事务内原子保存回答消息和全部通过校验的引用，避免出现只有回答没有引用的中间态。
     *
     * @param message 回答消息持久化实体
     * @param citations 通过逐字反查的引用实体列表
     */
    @Transactional
    public void saveAnswerWithCitations(
            ConversationMessageEntity message,
            List<MessageCitationEntity> citations
    ) {
        // 先写回答主体，再按引用顺序写引用事实，任一失败整体回滚
        mapper.insert(message);
        citations.forEach(messageCitationRepository::save);
    }

    /**
     * 按会话恢复全部消息，按创建时间和标识稳定排序。
     *
     * @param spaceId 知识空间标识
     * @param conversationId 会话标识
     * @return 会话消息实体列表
     */
    public List<ConversationMessageEntity> findByConversation(
            Long spaceId,
            Long conversationId
    ) {
        // 限定空间隔离并按时间顺序恢复完整历史
        return mapper.selectList(Wrappers.lambdaQuery(ConversationMessageEntity.class)
                .eq(ConversationMessageEntity::getSpaceId, spaceId)
                .eq(ConversationMessageEntity::getConversationId, conversationId)
                .orderByAsc(ConversationMessageEntity::getCreatedAt)
                .orderByAsc(ConversationMessageEntity::getId));
    }

    /**
     * 查询会话内指定消息，保证消息归属当前会话和空间。
     *
     * @param spaceId 知识空间标识
     * @param conversationId 会话标识
     * @param messageId 消息标识
     * @return 会话内消息实体
     */
    public Optional<ConversationMessageEntity> findByMessageId(
            Long spaceId,
            Long conversationId,
            Long messageId
    ) {
        // 限定空间、会话和消息标识，防止跨会话读取消息
        return Optional.ofNullable(mapper.selectOne(Wrappers.lambdaQuery(ConversationMessageEntity.class)
                .eq(ConversationMessageEntity::getId, messageId)
                .eq(ConversationMessageEntity::getSpaceId, spaceId)
                .eq(ConversationMessageEntity::getConversationId, conversationId)));
    }
}

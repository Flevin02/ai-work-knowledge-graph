package com.flevin.knowgraph.server.repository.conversation;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.flevin.knowgraph.server.repository.entity.MessageCitationEntity;
import com.flevin.knowgraph.server.repository.mapper.MessageCitationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 回答引用数据访问对象，负责引用事实保存和按消息恢复。
 */
@Repository
@RequiredArgsConstructor
public class MessageCitationRepository {

    private final MessageCitationMapper mapper;

    /**
     * 保存一条回答引用。
     *
     * @param entity 引用持久化实体
     */
    public void save(MessageCitationEntity entity) {
        // 将引用实体写入 message_citations 表；主键由应用侧 Snowflake 生成
        mapper.insert(entity);
    }

    /**
     * 按消息恢复全部引用，按引用顺序稳定排序。
     *
     * @param spaceId 知识空间标识
     * @param messageId 回答消息标识
     * @return 引用实体列表
     */
    public List<MessageCitationEntity> findByMessage(
            Long spaceId,
            Long messageId
    ) {
        // 限定空间隔离并按引用顺序恢复全部引用
        return mapper.selectList(Wrappers.lambdaQuery(MessageCitationEntity.class)
                .eq(MessageCitationEntity::getSpaceId, spaceId)
                .eq(MessageCitationEntity::getMessageId, messageId)
                .orderByAsc(MessageCitationEntity::getCitationOrder)
                .orderByAsc(MessageCitationEntity::getId));
    }

    /**
     * 批量恢复多条消息的引用，避免逐消息查询形成 N+1。
     *
     * @param spaceId 知识空间标识
     * @param messageIds 回答消息标识列表
     * @return 引用实体列表，按消息内引用顺序返回
     */
    public List<MessageCitationEntity> findByMessageIds(
            Long spaceId,
            List<Long> messageIds
    ) {
        // 空列表直接返回，避免生成无谓的 IN 查询
        if (messageIds.isEmpty()) {
            return List.of();
        }
        // 一次 IN 查询恢复全部引用，上层按消息标识分组
        return mapper.selectList(Wrappers.lambdaQuery(MessageCitationEntity.class)
                .eq(MessageCitationEntity::getSpaceId, spaceId)
                .in(MessageCitationEntity::getMessageId, messageIds)
                .orderByAsc(MessageCitationEntity::getMessageId)
                .orderByAsc(MessageCitationEntity::getCitationOrder)
                .orderByAsc(MessageCitationEntity::getId));
    }
}

package com.flevin.knowgraph.server.repository.graph;

import com.flevin.knowgraph.server.repository.entity.ReviewActionEntity;
import com.flevin.knowgraph.server.repository.mapper.ReviewActionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 图谱关系审核动作数据访问对象。
 */
@Repository
@RequiredArgsConstructor
public class ReviewActionRepository {

    private final ReviewActionMapper reviewActionMapper;

    /**
     * 保存一条关系审核动作，保留完整审核历史。
     *
     * @param action 已组装的审核动作
     */
    public void save(ReviewActionEntity action) {
        // 使用 MyBatis-Plus 保存审核历史，避免覆盖同一关系的既有动作
        reviewActionMapper.insert(action);
    }
}

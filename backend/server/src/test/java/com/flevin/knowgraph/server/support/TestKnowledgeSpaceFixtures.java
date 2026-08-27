package com.flevin.knowgraph.server.support;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 为依赖固定空间标识的集成测试准备隔离测试空间。
 */
public final class TestKnowledgeSpaceFixtures {

    public static final Long DEFAULT_SPACE_ID = 1_000_000_000_001L;

    private TestKnowledgeSpaceFixtures() {
    }

    /**
     * 确保测试数据库存在可重复使用的固定空间；生产 schema 不再初始化默认空间。
     *
     * @param jdbcTemplate 测试数据库访问模板
     */
    public static void ensureDefaultSpace(JdbcTemplate jdbcTemplate) {
        // 为依赖固定标识的测试插入独立空间，不影响生产首次启动空态
        jdbcTemplate.update("""
                INSERT IGNORE INTO knowledge_spaces (
                    id, name, description, status, created_at, updated_at
                ) VALUES (?, ?, ?, 'active', ?, ?)
                """,
                DEFAULT_SPACE_ID,
                "测试固定空间",
                "仅用于集成测试",
                "2026-08-17T00:00:00Z",
                "2026-08-17T00:00:00Z"
        );

        // 每个测试开始前恢复固定空间有效状态
        jdbcTemplate.update(
                "UPDATE knowledge_spaces SET status = 'active' WHERE id = ?",
                DEFAULT_SPACE_ID
        );
    }
}

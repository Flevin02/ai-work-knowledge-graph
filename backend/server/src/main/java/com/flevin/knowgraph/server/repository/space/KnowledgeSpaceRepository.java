package com.flevin.knowgraph.server.repository.space;

import com.flevin.knowgraph.server.model.space.KnowledgeSpace;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 知识空间数据访问对象，负责有效空间查询、创建和软删除。
 */
@Repository
public class KnowledgeSpaceRepository {

    private static final String FIND_ACTIVE_SQL = """
            SELECT id, name, description, status, created_at, updated_at
            FROM knowledge_spaces
            WHERE status = 'active'
            ORDER BY created_at ASC, id ASC
            """;

    private static final String FIND_ACTIVE_BY_ID_SQL = """
            SELECT id, name, description, status, created_at, updated_at
            FROM knowledge_spaces
            WHERE id = ? AND status = 'active'
            """;

    private static final String EXISTS_ACTIVE_NAME_SQL = """
            SELECT COUNT(1)
            FROM knowledge_spaces
            WHERE name = ? AND status = 'active'
            """;

    private static final String INSERT_SQL = """
            INSERT INTO knowledge_spaces (
                id, name, description, status, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?)
            """;

    private static final String SOFT_DELETE_SQL = """
            UPDATE knowledge_spaces
            SET status = 'deleted', updated_at = ?
            WHERE id = ? AND status = 'active'
            """;

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeSpaceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 查询全部有效知识空间。
     *
     * @return 按创建时间排序的有效知识空间
     */
    public List<KnowledgeSpace> findAllActive() {
        // 查询未被软删除的全部知识空间
        return jdbcTemplate.query(FIND_ACTIVE_SQL, this::mapSpace);
    }

    /**
     * 按标识查询有效知识空间。
     *
     * @param spaceId 知识空间标识
     * @return 有效知识空间；不存在或已删除时返回空
     */
    public Optional<KnowledgeSpace> findActiveById(String spaceId) {
        // 查询指定标识且状态有效的知识空间
        return jdbcTemplate.query(FIND_ACTIVE_BY_ID_SQL, this::mapSpace, spaceId)
                .stream()
                .findFirst();
    }

    /**
     * 判断是否存在同名有效知识空间。
     *
     * @param name 规范化后的知识空间名称
     * @return 存在同名有效空间时返回 true
     */
    public boolean existsActiveByName(String name) {
        // 统计同名且有效的知识空间数量
        Integer count = jdbcTemplate.queryForObject(EXISTS_ACTIVE_NAME_SQL, Integer.class, name);
        return count != null && count > 0;
    }

    /**
     * 统计有效知识空间数量。
     *
     * @return 有效知识空间数量
     */
    public int countActive() {
        // 统计当前未被软删除的知识空间数量
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM knowledge_spaces WHERE status = 'active'",
                Integer.class
        );
        return count == null ? 0 : count;
    }

    /**
     * 保存新知识空间。
     *
     * @param space 新知识空间模型
     */
    public void save(KnowledgeSpace space) {
        // 持久化知识空间基本信息和时间字段
        jdbcTemplate.update(
                INSERT_SQL,
                space.id(),
                space.name(),
                space.description(),
                space.status(),
                space.createdAt().toString(),
                space.updatedAt().toString()
        );
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
        // 更新空间状态并保留全部事实来源和关联数据
        return jdbcTemplate.update(SOFT_DELETE_SQL, updatedAt.toString(), spaceId);
    }

    /**
     * 将 JDBC 查询结果映射为知识空间模型。
     *
     * @param resultSet JDBC 查询结果
     * @param rowNumber 当前结果行号
     * @return 知识空间模型
     * @throws SQLException 字段读取失败时抛出
     */
    private KnowledgeSpace mapSpace(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        // 从数据库字段恢复知识空间和 ISO-8601 时间
        return new KnowledgeSpace(
                resultSet.getString("id"),
                resultSet.getString("name"),
                resultSet.getString("description"),
                resultSet.getString("status"),
                Instant.parse(resultSet.getString("created_at")),
                Instant.parse(resultSet.getString("updated_at"))
        );
    }
}

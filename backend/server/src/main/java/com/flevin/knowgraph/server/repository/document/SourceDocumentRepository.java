package com.flevin.knowgraph.server.repository.document;

import com.flevin.knowgraph.server.model.document.SourceDocument;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 来源资料数据访问对象，负责来源记录的保存和内容指纹查询。
 */
@Repository
public class SourceDocumentRepository {

    private static final String INSERT_SQL = """
            INSERT INTO source_documents (
                id, batch_id, name, kind, content_hash, storage_path, content_text,
                excerpt, status, file_size, imported_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String FIND_BY_CONTENT_HASH_SQL = """
            SELECT id, batch_id, name, kind, content_hash, storage_path, content_text,
                   excerpt, status, file_size, imported_at, updated_at
            FROM source_documents
            WHERE content_hash = ?
            """;

    private static final String FIND_ALL_SQL = """
            SELECT id, batch_id, name, kind, content_hash, storage_path, content_text,
                   excerpt, status, file_size, imported_at, updated_at
            FROM source_documents
            ORDER BY imported_at DESC, id DESC
            """;

    private final JdbcTemplate jdbcTemplate;

    public SourceDocumentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 保存已完成解析和原始文件落盘的来源资料。
     *
     * @param document 来源资料模型
     */
    public void save(SourceDocument document) {
        // 保存来源资料的结构化索引、解析文本和原始文件定位信息
        jdbcTemplate.update(
                INSERT_SQL,
                document.id(),
                document.batchId(),
                document.name(),
                document.kind(),
                document.contentHash(),
                document.storagePath(),
                document.contentText(),
                document.excerpt(),
                document.status(),
                document.fileSize(),
                document.importedAt().toString(),
                document.updatedAt().toString()
        );
    }

    /**
     * 按 SHA-256 内容指纹查询已导入的来源资料。
     *
     * @param contentHash SHA-256 内容指纹
     * @return 已存在的来源资料；不存在时返回空
     */
    public Optional<SourceDocument> findByContentHash(String contentHash) {
        // 查询相同内容指纹的来源资料，并只返回唯一记录
        return jdbcTemplate.query(FIND_BY_CONTENT_HASH_SQL, this::mapDocument, contentHash)
                .stream()
                .findFirst();
    }

    /**
     * 查询全部有效来源资料，按首次导入时间倒序返回。
     *
     * @return 来源资料列表
     */
    public List<SourceDocument> findAll() {
        // 查询当前数据库中全部来源资料摘要所需字段
        return jdbcTemplate.query(FIND_ALL_SQL, this::mapDocument);
    }

    /**
     * 将 JDBC 查询结果映射为来源资料模型。
     *
     * @param resultSet JDBC 查询结果
     * @param rowNumber 当前结果行号
     * @return 来源资料模型
     * @throws SQLException 字段读取失败时抛出
     */
    private SourceDocument mapDocument(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        // 从持久化字段恢复来源资料模型和 ISO-8601 时间
        return new SourceDocument(
                resultSet.getString("id"),
                resultSet.getString("batch_id"),
                resultSet.getString("name"),
                resultSet.getString("kind"),
                resultSet.getString("content_hash"),
                resultSet.getString("storage_path"),
                resultSet.getString("content_text"),
                resultSet.getString("excerpt"),
                resultSet.getString("status"),
                resultSet.getLong("file_size"),
                Instant.parse(resultSet.getString("imported_at")),
                Instant.parse(resultSet.getString("updated_at"))
        );
    }
}

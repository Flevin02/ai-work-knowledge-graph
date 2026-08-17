-- 来源资料导入批次：记录一次 multipart 请求的整体处理状态和分类统计。
CREATE TABLE IF NOT EXISTS import_batches (
    -- 导入批次唯一标识，使用 UUID。
    id TEXT PRIMARY KEY,
    -- 批次状态：processing、completed、partial_failed 或 failed。
    status TEXT NOT NULL,
    -- 本批次接收的文件总数。
    total_count INTEGER NOT NULL DEFAULT 0,
    -- 成功解析、落盘并新增来源记录的文件数。
    imported_count INTEGER NOT NULL DEFAULT 0,
    -- 内容指纹已存在、未重复落库的文件数。
    duplicate_count INTEGER NOT NULL DEFAULT 0,
    -- 文件类型不支持、内容为空或解析失败的文件数。
    failed_count INTEGER NOT NULL DEFAULT 0,
    -- 批次创建时间，使用 ISO-8601 UTC 字符串。
    created_at TEXT NOT NULL,
    -- 批次完成时间；处理过程中为空。
    completed_at TEXT
);

-- 来源资料：保存原始办公资料的结构化索引、解析文本和证据源定位。
CREATE TABLE IF NOT EXISTS source_documents (
    -- 来源资料唯一标识，使用 UUID。
    id TEXT PRIMARY KEY,
    -- 首次导入该内容的批次标识。
    batch_id TEXT NOT NULL,
    -- 用户上传时的原始文件名，仅用于展示，不参与服务端路径拼接。
    name TEXT NOT NULL,
    -- 文件类型：markdown 或 txt。
    kind TEXT NOT NULL,
    -- 原始文件字节内容的 SHA-256 指纹，用于重复导入识别。
    content_hash TEXT NOT NULL,
    -- 原始文件在服务端上传目录中的保存路径。
    storage_path TEXT NOT NULL,
    -- 按 UTF-8 解析得到的完整文本，后续作为 AI 抽取的事实源。
    content_text TEXT NOT NULL,
    -- 用于列表和导入结果展示的短文本预览。
    excerpt TEXT NOT NULL,
    -- 来源资料状态；当前阶段写入 active。
    status TEXT NOT NULL,
    -- 原始文件字节数。
    file_size INTEGER NOT NULL,
    -- 首次成功导入时间，使用 ISO-8601 UTC 字符串。
    imported_at TEXT NOT NULL,
    -- 最近更新时间，使用 ISO-8601 UTC 字符串。
    updated_at TEXT NOT NULL,
    -- 保证来源资料可以追溯到首次导入批次。
    FOREIGN KEY (batch_id) REFERENCES import_batches(id)
);

-- 同一字节内容只保留一份来源资料记录，是重复导入识别的最终数据库约束。
CREATE UNIQUE INDEX IF NOT EXISTS uk_source_documents_content_hash
    ON source_documents(content_hash);

-- 支持来源资料列表按最近导入时间倒序查询。
CREATE INDEX IF NOT EXISTS idx_source_documents_imported_at
    ON source_documents(imported_at DESC);

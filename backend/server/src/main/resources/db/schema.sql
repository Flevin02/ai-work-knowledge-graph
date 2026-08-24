-- 知识空间：作为文档、节点、关系、证据和审核记录的数据隔离根。
CREATE TABLE IF NOT EXISTS knowledge_spaces (
    -- 知识空间唯一标识，使用 UUID。
    id TEXT PRIMARY KEY,
    -- 用户可见的知识空间名称。
    name TEXT NOT NULL,
    -- 知识空间用途说明，可为空。
    description TEXT,
    -- 空间状态：active 或 deleted；删除采用软删除以保留事实来源。
    status TEXT NOT NULL,
    -- 空间创建时间，使用 ISO-8601 UTC 字符串。
    created_at TEXT NOT NULL,
    -- 空间最近更新时间，使用 ISO-8601 UTC 字符串。
    updated_at TEXT NOT NULL
);

-- 同一时间不允许存在两个同名的有效知识空间；已软删除名称可以重新使用。
CREATE UNIQUE INDEX IF NOT EXISTS uk_knowledge_spaces_active_name
    ON knowledge_spaces(name)
    WHERE status = 'active';

-- 来源资料导入批次：记录一次 multipart 请求的整体处理状态和分类统计。
CREATE TABLE IF NOT EXISTS import_batches (
    -- 导入批次唯一标识，使用 UUID。
    id TEXT PRIMARY KEY,
    -- 本批次所属知识空间。
    space_id TEXT NOT NULL,
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
    completed_at TEXT,
    -- 保证导入批次所属知识空间真实存在。
    FOREIGN KEY (space_id) REFERENCES knowledge_spaces(id)
);

-- 来源资料：保存原始办公资料的结构化索引、解析文本和证据源定位。
CREATE TABLE IF NOT EXISTS source_documents (
    -- 来源资料唯一标识，使用 UUID。
    id TEXT PRIMARY KEY,
    -- 来源资料所属知识空间。
    space_id TEXT NOT NULL,
    -- 首次导入该内容的批次标识。
    batch_id TEXT NOT NULL,
    -- 用户上传时的原始文件名，仅用于展示，不参与服务端路径拼接。
    name TEXT NOT NULL,
    -- 文件类型：markdown 或 txt。
    kind TEXT NOT NULL,
    -- 文档业务类型：general 或 prd；与文件格式字段 kind 相互独立。
    document_type TEXT NOT NULL DEFAULT 'general',
    -- 原始文件字节内容的 SHA-256 指纹，用于空间内重复导入识别。
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
    -- 保证来源资料所属知识空间真实存在。
    FOREIGN KEY (space_id) REFERENCES knowledge_spaces(id),
    -- 保证来源资料可以追溯到首次导入批次。
    FOREIGN KEY (batch_id) REFERENCES import_batches(id)
);

-- 图谱节点：保存项目、部门、人员、任务、文档、会议、风险和决策等实体。
CREATE TABLE IF NOT EXISTS graph_nodes (
    -- 图谱节点唯一标识，使用 UUID 或可稳定复用的领域标识。
    id TEXT PRIMARY KEY,
    -- 节点所属知识空间。
    space_id TEXT NOT NULL,
    -- 节点类型：project、department、person、task、document、meeting、risk 或 decision。
    node_type TEXT NOT NULL,
    -- 节点展示名称。
    label TEXT NOT NULL,
    -- 节点摘要，可为空。
    summary TEXT,
    -- 节点状态：suggested、active、completed、pending、conflict、orphan 或 stale。
    status TEXT NOT NULL,
    -- 支持同名实体规范化和去重的稳定键，可为空。
    normalized_key TEXT,
    -- 支撑节点结论的来源资料标识 JSON 数组。
    source_ids_json TEXT NOT NULL DEFAULT '[]',
    -- 节点创建时间，使用 ISO-8601 UTC 字符串。
    created_at TEXT NOT NULL,
    -- 节点最近更新时间，使用 ISO-8601 UTC 字符串。
    updated_at TEXT NOT NULL,
    -- 保证节点所属知识空间真实存在。
    FOREIGN KEY (space_id) REFERENCES knowledge_spaces(id)
);

-- 图谱关系：保存节点之间的候选、已确认、已拒绝或已失效关系。
CREATE TABLE IF NOT EXISTS graph_edges (
    -- 图谱关系唯一标识。
    id TEXT PRIMARY KEY,
    -- 关系所属知识空间。
    space_id TEXT NOT NULL,
    -- 关系主体节点标识。
    source_node_id TEXT NOT NULL,
    -- 关系客体节点标识。
    target_node_id TEXT NOT NULL,
    -- 关系类型，例如“负责”“属于项目”“项目任务”。
    relation_type TEXT NOT NULL,
    -- 关系状态：suggested、confirmed、rejected 或 stale。
    status TEXT NOT NULL,
    -- 关系置信度，规则或人工关系也使用 0～1 表示。
    confidence REAL NOT NULL,
    -- 关系创建时间，使用 ISO-8601 UTC 字符串。
    created_at TEXT NOT NULL,
    -- 关系最近更新时间，使用 ISO-8601 UTC 字符串。
    updated_at TEXT NOT NULL,
    -- 保证关系所属知识空间真实存在。
    FOREIGN KEY (space_id) REFERENCES knowledge_spaces(id),
    -- 保证主体节点真实存在。
    FOREIGN KEY (source_node_id) REFERENCES graph_nodes(id),
    -- 保证客体节点真实存在。
    FOREIGN KEY (target_node_id) REFERENCES graph_nodes(id)
);

-- 关系证据：每条关系必须通过来源资料中的原文片段说明其依据。
CREATE TABLE IF NOT EXISTS evidences (
    -- 证据唯一标识。
    id TEXT PRIMARY KEY,
    -- 证据所属知识空间。
    space_id TEXT NOT NULL,
    -- 证据所支撑的图谱关系标识。
    edge_id TEXT NOT NULL,
    -- 证据来源资料标识。
    source_document_id TEXT NOT NULL,
    -- 来源资料中的原文片段。
    quote TEXT NOT NULL,
    -- 段落、标题、行号或页码等定位信息，可为空。
    locator TEXT,
    -- 证据提取方式：ai、rule 或 user。
    extraction_method TEXT NOT NULL,
    -- 证据创建时间，使用 ISO-8601 UTC 字符串。
    created_at TEXT NOT NULL,
    -- 保证证据所属知识空间真实存在。
    FOREIGN KEY (space_id) REFERENCES knowledge_spaces(id),
    -- 保证证据所支撑的关系真实存在。
    FOREIGN KEY (edge_id) REFERENCES graph_edges(id),
    -- 保证证据可以追溯到原始来源资料。
    FOREIGN KEY (source_document_id) REFERENCES source_documents(id)
);

-- AI 抽取运行：记录一次来源资料抽取的模型、版本、状态、结果和失败上下文。
CREATE TABLE IF NOT EXISTS ai_extraction_runs (
    -- 抽取运行唯一标识。
    id TEXT PRIMARY KEY,
    -- 抽取运行所属知识空间。
    space_id TEXT NOT NULL,
    -- 被抽取的来源资料标识。
    source_document_id TEXT NOT NULL,
    -- 当前模型协议或供应商标识。
    provider TEXT NOT NULL,
    -- 当前聊天模型名称。
    model TEXT NOT NULL,
    -- 本次使用的 Prompt 版本。
    prompt_version TEXT NOT NULL,
    -- 本次使用的结构化输出 Schema 版本。
    schema_version TEXT NOT NULL,
    -- 运行状态：processing、completed 或 failed。
    status TEXT NOT NULL,
    -- 解析得到的章节数量。
    section_count INTEGER NOT NULL DEFAULT 0,
    -- 实际抽取的分片数量。
    chunk_count INTEGER NOT NULL DEFAULT 0,
    -- 成功运行的完整结构化结果 JSON；失败时为空。
    result_json TEXT,
    -- 成功运行按分片顺序聚合的文档摘要，供来源资料列表展示；失败或旧版运行可为空。
    document_summary TEXT,
    -- 文档级全文摘要使用的独立 Prompt 版本。
    document_summary_prompt_version TEXT,
    -- 文档级全文摘要状态：not_started、completed 或 failed。
    document_summary_status TEXT,
    -- 文档级全文摘要失败原因；摘要成功或未开始时为空。
    document_summary_error TEXT,
    -- 失败或校验错误摘要；成功时为空。
    error_message TEXT,
    -- 运行创建时间，使用 ISO-8601 UTC 字符串。
    created_at TEXT NOT NULL,
    -- 运行完成或失败时间；处理中为空。
    completed_at TEXT,
    -- 保证运行所属知识空间真实存在。
    FOREIGN KEY (space_id) REFERENCES knowledge_spaces(id),
    -- 保证运行能够追溯到来源资料。
    FOREIGN KEY (source_document_id) REFERENCES source_documents(id)
);

-- 标签字典：保存知识空间内可复用的规范化标签，不承载具体文档的审核状态。
CREATE TABLE IF NOT EXISTS tags (
    -- 标签唯一标识。
    id TEXT PRIMARY KEY,
    -- 标签所属知识空间。
    space_id TEXT NOT NULL,
    -- 面向用户展示的标签名称。
    name TEXT NOT NULL,
    -- 轻量规范化键，用于合并大小写、首尾空格和连续空格差异。
    normalized_key TEXT NOT NULL,
    -- 标签字典状态；文档标签审核状态由 document_tags 单独维护。
    status TEXT NOT NULL CHECK (status IN ('active', 'inactive')),
    -- 标签创建时间，使用 ISO-8601 UTC 字符串。
    created_at TEXT NOT NULL,
    -- 标签最近更新时间，使用 ISO-8601 UTC 字符串。
    updated_at TEXT NOT NULL,
    -- 保证标签所属知识空间真实存在。
    FOREIGN KEY (space_id) REFERENCES knowledge_spaces(id)
);

-- 同一知识空间内只保留一份规范化标签定义。
CREATE UNIQUE INDEX IF NOT EXISTS uk_tags_space_normalized_key
    ON tags(space_id, normalized_key);

-- 文档标签：保存 AI 候选、用户确认、拒绝或失效的文档标签关系。
CREATE TABLE IF NOT EXISTS document_tags (
    -- 文档标签关系唯一标识。
    id TEXT PRIMARY KEY,
    -- 文档标签所属知识空间。
    space_id TEXT NOT NULL,
    -- 被标记的真实来源资料。
    source_document_id TEXT NOT NULL,
    -- 复用的空间内标签定义。
    tag_id TEXT NOT NULL,
    -- 标签来源：ai、user 或预留的 rule。
    source_type TEXT NOT NULL CHECK (source_type IN ('ai', 'user', 'rule')),
    -- 文档标签状态：suggested、confirmed、rejected 或 stale。
    status TEXT NOT NULL CHECK (status IN ('suggested', 'confirmed', 'rejected', 'stale')),
    -- AI 或规则对候选标签的估计置信度；用户手工标签为空。
    confidence REAL CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1)),
    -- 可选标签抽取运行标识；标签运行表在后续 AI 小切片确定后再增加外键。
    extraction_run_id TEXT,
    -- 生成或确认标签时的来源文档内容指纹。
    content_hash TEXT NOT NULL,
    -- AI 标签 Prompt 版本；用户手工标签为空。
    prompt_version TEXT,
    -- AI 标签 Schema 版本；用户手工标签为空。
    schema_version TEXT,
    -- 按空间、文档、内容指纹、规范化标签和 Prompt/Schema 版本计算的稳定幂等键。
    document_tag_key TEXT NOT NULL,
    -- 文档标签创建时间，使用 ISO-8601 UTC 字符串。
    created_at TEXT NOT NULL,
    -- 文档标签最近更新时间，使用 ISO-8601 UTC 字符串。
    updated_at TEXT NOT NULL,
    -- AI 标签必须保留置信度和版本快照；用户标签不伪造模型字段。
    -- 初始 suggested/confirmed 边界由 Service 校验，允许后续审核或内容变化更新状态。
    CHECK (
        (source_type = 'ai' AND confidence IS NOT NULL
            AND prompt_version IS NOT NULL AND schema_version IS NOT NULL)
        OR (source_type = 'user' AND confidence IS NULL
            AND prompt_version IS NULL AND schema_version IS NULL)
        OR source_type = 'rule'
    ),
    -- 保证文档标签所属知识空间真实存在。
    FOREIGN KEY (space_id) REFERENCES knowledge_spaces(id),
    -- 保证被标记的来源资料真实存在。
    FOREIGN KEY (source_document_id) REFERENCES source_documents(id),
    -- 保证标签定义真实存在。
    FOREIGN KEY (tag_id) REFERENCES tags(id)
);

-- 相同资料内容、标签和 Prompt/Schema 版本重复运行时复用同一文档标签记录。
CREATE UNIQUE INDEX IF NOT EXISTS uk_document_tags_space_tag_key
    ON document_tags(space_id, document_tag_key);

-- 支持按空间、来源资料和状态恢复标签列表。
CREATE INDEX IF NOT EXISTS idx_document_tags_space_document_status
    ON document_tags(space_id, source_document_id, status, updated_at DESC);

-- 文档标签证据：保存 AI 候选标签在当前来源资料中的逐字依据。
CREATE TABLE IF NOT EXISTS document_tag_evidences (
    -- 标签证据唯一标识。
    id TEXT PRIMARY KEY,
    -- 标签证据所属知识空间。
    space_id TEXT NOT NULL,
    -- 被证据支撑的文档标签关系。
    document_tag_id TEXT NOT NULL,
    -- 证据实际所在的来源资料，必须与文档标签主体一致。
    source_document_id TEXT NOT NULL,
    -- 当前文档内稳定的分片标识。
    chunk_id TEXT NOT NULL,
    -- 分片所属章节路径。
    section_path TEXT NOT NULL,
    -- 可逐字反查的原文片段。
    quote TEXT NOT NULL,
    -- 原文起始偏移，使用半开区间；未知时为空。
    start_offset INTEGER CHECK (start_offset IS NULL OR start_offset >= 0),
    -- 原文结束偏移，不包含该位置字符；未知时为空。
    end_offset INTEGER CHECK (end_offset IS NULL OR end_offset >= 0),
    -- 证据创建时间，使用 ISO-8601 UTC 字符串。
    created_at TEXT NOT NULL,
    -- 保证证据所属知识空间真实存在。
    FOREIGN KEY (space_id) REFERENCES knowledge_spaces(id),
    -- 保证证据支撑的文档标签关系真实存在。
    FOREIGN KEY (document_tag_id) REFERENCES document_tags(id),
    -- 保证证据来源资料真实存在。
    FOREIGN KEY (source_document_id) REFERENCES source_documents(id),
    -- 已知偏移必须保持半开区间顺序。
    CHECK (
        start_offset IS NULL OR end_offset IS NULL OR end_offset >= start_offset
    )
);

-- 支持按文档标签关系稳定恢复全部证据。
CREATE INDEX IF NOT EXISTS idx_document_tag_evidences_document_tag_created_at
    ON document_tag_evidences(space_id, document_tag_id, created_at, id);

-- 文档关联运行：保存一次文档候选关联分析的版本、状态、召回统计和失败上下文。
CREATE TABLE IF NOT EXISTS document_association_runs (
    -- 关联运行唯一标识。
    id TEXT PRIMARY KEY,
    -- 关联运行所属知识空间。
    space_id TEXT NOT NULL,
    -- 本次作为关联主体的来源资料。
    source_document_id TEXT NOT NULL,
    -- 运行开始时主体文档的内容指纹。
    source_content_hash TEXT NOT NULL,
    -- 运行状态：processing、completed 或 failed。
    status TEXT NOT NULL CHECK (status IN ('processing', 'completed', 'failed')),
    -- 失败阶段，例如 retrieval_failed、association_model_failed 或 evidence_invalid。
    failure_stage TEXT,
    -- 面向用户或运维的稳定错误摘要。
    error_message TEXT,
    -- 所有召回通道合并前的候选数量。
    candidate_count INTEGER NOT NULL DEFAULT 0 CHECK (candidate_count >= 0),
    -- 实际交给关联判断模型比较的候选数量。
    compared_count INTEGER NOT NULL DEFAULT 0 CHECK (compared_count >= 0),
    -- 最终写入的候选关系建议数量。
    suggestion_count INTEGER NOT NULL DEFAULT 0 CHECK (suggestion_count >= 0),
    -- 标签通道候选数量；阶段 1 默认保持 0。
    tag_candidate_count INTEGER NOT NULL DEFAULT 0 CHECK (tag_candidate_count >= 0),
    -- 关键词、标题和显式引用通道候选数量。
    keyword_candidate_count INTEGER NOT NULL DEFAULT 0 CHECK (keyword_candidate_count >= 0),
    -- 语义通道候选数量；Embedding 未启用时保持 0。
    semantic_candidate_count INTEGER NOT NULL DEFAULT 0 CHECK (semantic_candidate_count >= 0),
    -- 关联判断 Prompt 版本。
    prompt_version TEXT NOT NULL,
    -- 关联判断 Schema 版本。
    schema_version TEXT NOT NULL,
    -- 候选召回策略版本。
    candidate_recall_policy_version TEXT NOT NULL,
    -- 文档关联策略版本。
    association_policy_version TEXT NOT NULL,
    -- 可选 Embedding 供应商快照。
    embedding_provider TEXT,
    -- 可选 Embedding 模型快照。
    embedding_model TEXT,
    -- 可选 Embedding 版本快照。
    embedding_version TEXT,
    -- 召回 TopK 快照。
    top_k INTEGER CHECK (top_k IS NULL OR top_k > 0),
    -- 召回相似度阈值快照；未启用向量时为空。
    similarity_threshold REAL CHECK (
        similarity_threshold IS NULL OR (similarity_threshold >= 0 AND similarity_threshold <= 1)
    ),
    -- 聊天模型请求次数。
    model_request_count INTEGER NOT NULL DEFAULT 0 CHECK (model_request_count >= 0),
    -- 重试次数。
    retry_count INTEGER NOT NULL DEFAULT 0 CHECK (retry_count >= 0),
    -- 运行耗时毫秒。
    duration_ms INTEGER CHECK (duration_ms IS NULL OR duration_ms >= 0),
    -- 运行创建时间，使用 ISO-8601 UTC 字符串。
    created_at TEXT NOT NULL,
    -- 运行完成或失败时间；处理中为空。
    completed_at TEXT,
    -- 保证运行所属知识空间真实存在。
    FOREIGN KEY (space_id) REFERENCES knowledge_spaces(id),
    -- 保证关联主体文档真实存在。
    FOREIGN KEY (source_document_id) REFERENCES source_documents(id)
);

-- 文档关联：保存真实来源文档之间的候选、确认、拒绝或失效关系。
CREATE TABLE IF NOT EXISTS document_relations (
    -- 文档关系唯一标识。
    id TEXT PRIMARY KEY,
    -- 文档关系所属知识空间。
    space_id TEXT NOT NULL,
    -- 有向关系主体；对称关系保存规范化排序后的左侧文档。
    source_document_id TEXT NOT NULL,
    -- 有向关系客体；对称关系保存规范化排序后的右侧文档。
    target_document_id TEXT NOT NULL,
    -- 第一版固定关系类型白名单。
    relation_type TEXT NOT NULL CHECK (
        relation_type IN ('related_to', 'references', 'supports', 'updates', 'conflicts_with')
    ),
    -- 关系方向：有向关系使用 current_to_candidate/candidate_to_current，
    -- 对称关系使用 symmetric。
    direction TEXT NOT NULL CHECK (
        (relation_type IN ('related_to', 'conflicts_with') AND direction = 'symmetric')
        OR (relation_type IN ('references', 'supports', 'updates')
            AND direction IN ('current_to_candidate', 'candidate_to_current'))
    ),
    -- 关系状态：suggested、confirmed、rejected 或 stale。
    status TEXT NOT NULL CHECK (status IN ('suggested', 'confirmed', 'rejected', 'stale')),
    -- 关系生成方式，用于解释和评估，不替代关系类型。
    generation_mode TEXT NOT NULL CHECK (
        generation_mode IN (
            'explicit_reference',
            'tag_match',
            'keyword_match',
            'semantic_match',
            'hybrid',
            'user'
        )
    ),
    -- 模型或规则对候选关系的估计置信度。
    confidence REAL NOT NULL CHECK (confidence >= 0 AND confidence <= 1),
    -- 供人工审核阅读的关系原因。
    reason TEXT NOT NULL,
    -- 产生关系的文档关联运行；手工关系可以为空。
    association_run_id TEXT,
    -- 关系主体内容指纹快照。
    source_content_hash TEXT NOT NULL,
    -- 关系客体内容指纹快照。
    target_content_hash TEXT NOT NULL,
    -- 关系策略版本快照。
    association_policy_version TEXT NOT NULL,
    -- 按空间、关系类型、规范化文档端点和内容指纹计算的稳定幂等键。
    relation_key TEXT NOT NULL,
    -- 关系创建时间，使用 ISO-8601 UTC 字符串。
    created_at TEXT NOT NULL,
    -- 关系最近更新时间，使用 ISO-8601 UTC 字符串。
    updated_at TEXT NOT NULL,
    -- 禁止文档自关联。
    CHECK (source_document_id != target_document_id),
    -- 保证关系所属知识空间真实存在。
    FOREIGN KEY (space_id) REFERENCES knowledge_spaces(id),
    -- 保证关系主体文档真实存在。
    FOREIGN KEY (source_document_id) REFERENCES source_documents(id),
    -- 保证关系客体文档真实存在。
    FOREIGN KEY (target_document_id) REFERENCES source_documents(id),
    -- 保证关联运行可以被历史追溯。
    FOREIGN KEY (association_run_id) REFERENCES document_association_runs(id)
);

-- 文档关系证据：保存关系两端或跨文档引用的可定位原文片段。
CREATE TABLE IF NOT EXISTS document_relation_evidences (
    -- 文档关系证据唯一标识。
    id TEXT PRIMARY KEY,
    -- 证据所属知识空间。
    space_id TEXT NOT NULL,
    -- 被证据支撑的文档关系。
    document_relation_id TEXT NOT NULL,
    -- 证据实际所在的来源资料；必须是关系两端之一。
    source_document_id TEXT NOT NULL,
    -- 当前文档内稳定的分片标识。
    chunk_id TEXT NOT NULL,
    -- 分片所属章节路径。
    section_path TEXT NOT NULL,
    -- 可逐字反查的原文片段。
    quote TEXT NOT NULL,
    -- 原文起始偏移，使用半开区间；未知时为空。
    start_offset INTEGER CHECK (start_offset IS NULL OR start_offset >= 0),
    -- 原文结束偏移，不包含该位置字符；未知时为空。
    end_offset INTEGER CHECK (end_offset IS NULL OR end_offset >= 0),
    -- 证据角色：source、target 或 cross_reference。
    evidence_role TEXT NOT NULL CHECK (
        evidence_role IN ('source', 'target', 'cross_reference')
    ),
    -- 证据创建时间，使用 ISO-8601 UTC 字符串。
    created_at TEXT NOT NULL,
    -- 保证证据所属知识空间真实存在。
    FOREIGN KEY (space_id) REFERENCES knowledge_spaces(id),
    -- 保证证据支撑的文档关系真实存在。
    FOREIGN KEY (document_relation_id) REFERENCES document_relations(id),
    -- 保证证据来源资料真实存在。
    FOREIGN KEY (source_document_id) REFERENCES source_documents(id),
    -- 已知偏移必须保持半开区间顺序。
    CHECK (
        start_offset IS NULL OR end_offset IS NULL OR end_offset >= start_offset
    )
);

-- 文档关系审核历史：保存不可变的采纳、拒绝和手工创建动作。
CREATE TABLE IF NOT EXISTS document_relation_reviews (
    -- 文档关系审核记录唯一标识。
    id TEXT PRIMARY KEY,
    -- 审核记录所属知识空间。
    space_id TEXT NOT NULL,
    -- 被审核的文档关系。
    document_relation_id TEXT NOT NULL,
    -- 审核动作：accept、reject 或 create。
    action TEXT NOT NULL CHECK (action IN ('accept', 'reject', 'create')),
    -- 拒绝原因、采纳说明或手工创建说明。
    reason TEXT,
    -- 操作者展示名称；本地单用户阶段默认 local-user。
    operator_name TEXT NOT NULL,
    -- 审核时间，使用 ISO-8601 UTC 字符串。
    created_at TEXT NOT NULL,
    -- 保证审核记录所属知识空间真实存在。
    FOREIGN KEY (space_id) REFERENCES knowledge_spaces(id),
    -- 保证审核目标关系真实存在。
    FOREIGN KEY (document_relation_id) REFERENCES document_relations(id)
);

-- 关系审核记录：保留接受、拒绝和修改动作，避免相同错误建议反复出现。
CREATE TABLE IF NOT EXISTS review_actions (
    -- 审核记录唯一标识。
    id TEXT PRIMARY KEY,
    -- 审核记录所属知识空间。
    space_id TEXT NOT NULL,
    -- 被审核的图谱关系标识。
    edge_id TEXT NOT NULL,
    -- 审核动作：accept、reject 或 modify。
    action TEXT NOT NULL,
    -- 拒绝原因或修改说明，可为空。
    reason TEXT,
    -- 操作者展示名称；本地单用户阶段默认 local-user。
    operator_name TEXT NOT NULL,
    -- 审核时间，使用 ISO-8601 UTC 字符串。
    created_at TEXT NOT NULL,
    -- 保证审核记录所属知识空间真实存在。
    FOREIGN KEY (space_id) REFERENCES knowledge_spaces(id),
    -- 保证被审核关系真实存在。
    FOREIGN KEY (edge_id) REFERENCES graph_edges(id)
);

-- 来源资料和导入批次的 space_id 兼容旧数据库升级，因此相关索引由
-- DatabaseSchemaInitializer 在字段迁移完成后统一创建。

-- 支持按知识空间、类型和状态筛选图谱节点。
CREATE INDEX IF NOT EXISTS idx_graph_nodes_space_type_status
    ON graph_nodes(space_id, node_type, status);

-- 同一知识空间内规范化键非空时保持唯一，避免重复实体。
CREATE UNIQUE INDEX IF NOT EXISTS uk_graph_nodes_space_normalized_key
    ON graph_nodes(space_id, normalized_key)
    WHERE normalized_key IS NOT NULL;

-- 支持按知识空间和状态查询图谱关系与待审核关系。
CREATE INDEX IF NOT EXISTS idx_graph_edges_space_status
    ON graph_edges(space_id, status);

-- 支持查询节点的一跳出边。
CREATE INDEX IF NOT EXISTS idx_graph_edges_source_node
    ON graph_edges(source_node_id);

-- 支持查询节点的一跳入边。
CREATE INDEX IF NOT EXISTS idx_graph_edges_target_node
    ON graph_edges(target_node_id);

-- 支持按关系查询证据。
CREATE INDEX IF NOT EXISTS idx_evidences_edge_id
    ON evidences(edge_id);

-- 支持按来源资料反查证据。
CREATE INDEX IF NOT EXISTS idx_evidences_source_document_id
    ON evidences(source_document_id);

-- 支持按文档查看最近抽取记录和状态。
CREATE INDEX IF NOT EXISTS idx_ai_extraction_runs_document_created_at
    ON ai_extraction_runs(source_document_id, created_at DESC);

-- 支持查询一条关系的完整审核历史。
CREATE INDEX IF NOT EXISTS idx_review_actions_edge_created_at
    ON review_actions(edge_id, created_at DESC);

-- 支持按空间、主体文档和创建时间查询关联运行。
CREATE INDEX IF NOT EXISTS idx_document_association_runs_space_source_created_at
    ON document_association_runs(space_id, source_document_id, created_at DESC);

-- 支持按空间和运行状态观察关联处理进度。
CREATE INDEX IF NOT EXISTS idx_document_association_runs_space_status
    ON document_association_runs(space_id, status, created_at DESC);

-- 同一空间内稳定关系键唯一，保证相同版本和内容指纹不重复生成建议。
CREATE UNIQUE INDEX IF NOT EXISTS uk_document_relations_space_relation_key
    ON document_relations(space_id, relation_key);

-- 支持按空间、文档和状态查询关系建议及审核结果。
CREATE INDEX IF NOT EXISTS idx_document_relations_space_source_status
    ON document_relations(space_id, source_document_id, status, updated_at DESC);

-- 支持按关系查询文档关系证据。
CREATE INDEX IF NOT EXISTS idx_document_relation_evidences_relation_created_at
    ON document_relation_evidences(document_relation_id, created_at ASC);

-- 支持按来源资料反查文档关系证据。
CREATE INDEX IF NOT EXISTS idx_document_relation_evidences_source_document
    ON document_relation_evidences(space_id, source_document_id, created_at ASC);

-- 支持按关系查询不可变审核历史。
CREATE INDEX IF NOT EXISTS idx_document_relation_reviews_relation_created_at
    ON document_relation_reviews(document_relation_id, created_at DESC);

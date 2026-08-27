-- MySQL 8.0 事实库全量建表脚本。
-- 运行约束：上线前在目标数据库执行；应用启动阶段不执行任何 DDL。
-- 主键和关联 ID 由应用侧 Snowflake 生成，使用有符号 BIGINT；本脚本不声明数据库外键。

CREATE TABLE knowledge_spaces
(
    id              BIGINT       NOT NULL COMMENT '知识空间唯一标识，由应用侧 Snowflake 生成',
    name            VARCHAR(512) NOT NULL COMMENT '用户可见的知识空间名称',
    description     TEXT                  COMMENT '知识空间用途说明',
    status          VARCHAR(32)  NOT NULL COMMENT '空间状态：active 或 deleted；删除采用软删除',
    created_at      VARCHAR(40)  NOT NULL COMMENT '空间创建时间，ISO-8601 UTC 字符串',
    updated_at      VARCHAR(40)  NOT NULL COMMENT '空间最近更新时间，ISO-8601 UTC 字符串',
    active_name_key VARCHAR(512) GENERATED ALWAYS AS
        (CASE WHEN status = 'active' THEN name ELSE NULL END) STORED
        COMMENT '仅为有效空间生成的名称键，用于软删除后复用名称',
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_spaces_active_name (active_name_key)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '知识空间及数据隔离根';

CREATE TABLE import_batches
(
    id              BIGINT      NOT NULL COMMENT '导入批次唯一标识，由应用侧 Snowflake 生成',
    space_id        BIGINT      NOT NULL COMMENT '所属知识空间标识，业务层校验空间归属',
    status          VARCHAR(32) NOT NULL COMMENT '批次状态：processing、completed、partial_failed 或 failed',
    total_count     INT         NOT NULL DEFAULT 0 COMMENT '本批次接收的文件总数',
    imported_count  INT         NOT NULL DEFAULT 0 COMMENT '成功解析、落盘并新增来源记录的文件数',
    duplicate_count INT         NOT NULL DEFAULT 0 COMMENT '内容指纹已存在、未重复落库的文件数',
    failed_count    INT         NOT NULL DEFAULT 0 COMMENT '文件类型不支持、内容为空或解析失败的文件数',
    created_at      VARCHAR(40) NOT NULL COMMENT '批次创建时间，ISO-8601 UTC 字符串',
    completed_at    VARCHAR(40)          COMMENT '批次完成时间，处理过程中为空',
    PRIMARY KEY (id),
    KEY idx_import_batches_space_created_at (space_id, created_at DESC, id DESC)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '来源资料导入批次';

CREATE TABLE source_documents
(
    id            BIGINT       NOT NULL COMMENT '来源资料唯一标识，由应用侧 Snowflake 生成',
    space_id      BIGINT       NOT NULL COMMENT '所属知识空间标识，业务层校验空间归属',
    batch_id      BIGINT       NOT NULL COMMENT '首次导入该内容的批次标识',
    name          VARCHAR(512) NOT NULL COMMENT '用户上传时的原始文件名，仅用于展示',
    kind          VARCHAR(32)  NOT NULL COMMENT '文件格式：markdown、txt 或 pdf',
    document_type VARCHAR(32)  NOT NULL DEFAULT 'general' COMMENT '文档业务类型：general 或 prd',
    content_hash  CHAR(64)     NOT NULL COMMENT '原始文件字节内容的 SHA-256 指纹',
    storage_path  TEXT         NOT NULL COMMENT '原始文件在服务端上传目录中的保存路径',
    content_text  LONGTEXT     NOT NULL COMMENT '按 UTF-8 解析得到的完整文本，作为 AI 事实源',
    excerpt       TEXT         NOT NULL COMMENT '用于列表和导入结果展示的短文本预览',
    status        VARCHAR(32)  NOT NULL COMMENT '来源资料状态；当前阶段写入 active',
    file_size     BIGINT       NOT NULL COMMENT '原始文件字节数',
    imported_at   VARCHAR(40)  NOT NULL COMMENT '首次成功导入时间，ISO-8601 UTC 字符串',
    updated_at    VARCHAR(40)  NOT NULL COMMENT '最近更新时间，ISO-8601 UTC 字符串',
    PRIMARY KEY (id),
    UNIQUE KEY uk_source_documents_space_hash (space_id, content_hash),
    KEY idx_source_documents_space_imported_at (space_id, imported_at DESC, id DESC),
    KEY idx_source_documents_space_status (space_id, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '来源办公资料及解析原文';

CREATE TABLE graph_nodes
(
    id              BIGINT       NOT NULL COMMENT '图谱节点唯一标识，由应用侧 Snowflake 生成',
    space_id        BIGINT       NOT NULL COMMENT '所属知识空间标识，业务层校验空间归属',
    node_type       VARCHAR(32)  NOT NULL COMMENT '节点类型：project、department、person、task、document、meeting、risk、decision、requirement 或 feature',
    label           VARCHAR(512) NOT NULL COMMENT '节点展示名称',
    summary         TEXT                  COMMENT '节点摘要',
    status          VARCHAR(32)  NOT NULL COMMENT '节点状态：suggested、active、completed、pending、conflict、orphan 或 stale',
    normalized_key  VARCHAR(255)          COMMENT '同一空间内用于实体规范化和去重的稳定键',
    source_ids_json VARCHAR(4000) NOT NULL DEFAULT '[]' COMMENT '支撑节点结论的来源资料 ID JSON 数组',
    created_at      VARCHAR(40)  NOT NULL COMMENT '节点创建时间，ISO-8601 UTC 字符串',
    updated_at      VARCHAR(40)  NOT NULL COMMENT '节点最近更新时间，ISO-8601 UTC 字符串',
    PRIMARY KEY (id),
    KEY idx_graph_nodes_space_type_status (space_id, node_type, status),
    UNIQUE KEY uk_graph_nodes_space_normalized_key (space_id, normalized_key)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '兼容实体图谱节点';

CREATE TABLE graph_edges
(
    id             BIGINT       NOT NULL COMMENT '图谱关系唯一标识，由应用侧 Snowflake 生成',
    space_id       BIGINT       NOT NULL COMMENT '所属知识空间标识，业务层校验空间归属',
    source_node_id BIGINT       NOT NULL COMMENT '关系主体节点标识',
    target_node_id BIGINT       NOT NULL COMMENT '关系客体节点标识',
    relation_type  VARCHAR(128) NOT NULL COMMENT '关系类型，例如负责、属于项目或项目任务',
    status         VARCHAR(32)  NOT NULL COMMENT '关系状态：suggested、confirmed、rejected 或 stale',
    confidence     DOUBLE       NOT NULL COMMENT '关系置信度，取值范围 0 到 1',
    created_at     VARCHAR(40)  NOT NULL COMMENT '关系创建时间，ISO-8601 UTC 字符串',
    updated_at     VARCHAR(40)  NOT NULL COMMENT '关系最近更新时间，ISO-8601 UTC 字符串',
    PRIMARY KEY (id),
    KEY idx_graph_edges_space_status (space_id, status),
    KEY idx_graph_edges_source_node (source_node_id),
    KEY idx_graph_edges_target_node (target_node_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '兼容实体图谱关系';

CREATE TABLE evidences
(
    id                 BIGINT       NOT NULL COMMENT '证据唯一标识，由应用侧 Snowflake 生成',
    space_id           BIGINT       NOT NULL COMMENT '所属知识空间标识，业务层校验空间归属',
    edge_id            BIGINT       NOT NULL COMMENT '所支撑的图谱关系标识',
    source_document_id BIGINT       NOT NULL COMMENT '证据来源资料标识',
    quote              TEXT         NOT NULL COMMENT '来源资料中的原文片段',
    locator            TEXT                  COMMENT '段落、标题、行号或页码等定位信息',
    extraction_method  VARCHAR(32)  NOT NULL COMMENT '证据提取方式：ai、rule 或 user',
    created_at         VARCHAR(40)  NOT NULL COMMENT '证据创建时间，ISO-8601 UTC 字符串',
    PRIMARY KEY (id),
    KEY idx_evidences_edge_id (edge_id),
    KEY idx_evidences_source_document_id (source_document_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '兼容实体关系证据';

CREATE TABLE ai_extraction_runs
(
    id                              BIGINT       NOT NULL COMMENT '抽取运行唯一标识，由应用侧 Snowflake 生成',
    space_id                        BIGINT       NOT NULL COMMENT '所属知识空间标识，业务层校验空间归属',
    source_document_id              BIGINT       NOT NULL COMMENT '被抽取的来源资料标识',
    provider                        VARCHAR(128) NOT NULL COMMENT '模型协议或供应商标识',
    model                           VARCHAR(128) NOT NULL COMMENT '聊天模型名称',
    prompt_version                  VARCHAR(128) NOT NULL COMMENT '本次使用的 Prompt 版本',
    schema_version                  VARCHAR(128) NOT NULL COMMENT '本次使用的结构化输出 Schema 版本',
    status                          VARCHAR(32)  NOT NULL COMMENT '运行状态：processing、completed 或 failed',
    section_count                   INT          NOT NULL DEFAULT 0 COMMENT '解析得到的章节数量',
    chunk_count                     INT          NOT NULL DEFAULT 0 COMMENT '实际抽取的分片数量',
    result_json                     LONGTEXT              COMMENT '成功运行的完整结构化结果 JSON',
    document_summary                TEXT                  COMMENT '成功运行按分片顺序聚合的文档摘要',
    document_summary_prompt_version VARCHAR(128)          COMMENT '文档级全文摘要使用的 Prompt 版本',
    document_summary_status         VARCHAR(32)           COMMENT '摘要状态：not_started、completed 或 failed',
    document_summary_error          TEXT                  COMMENT '文档级摘要失败原因',
    error_message                   TEXT                  COMMENT '失败或校验错误摘要',
    created_at                      VARCHAR(40)  NOT NULL COMMENT '运行创建时间，ISO-8601 UTC 字符串',
    completed_at                    VARCHAR(40)           COMMENT '运行完成或失败时间，处理中为空',
    PRIMARY KEY (id),
    KEY idx_ai_extraction_runs_document_created_at (source_document_id, created_at DESC, id DESC)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'AI 实体关系抽取运行记录';

CREATE TABLE tags
(
    id             BIGINT       NOT NULL COMMENT '标签定义唯一标识，由应用侧 Snowflake 生成',
    space_id       BIGINT       NOT NULL COMMENT '所属知识空间标识，业务层校验空间归属',
    name           VARCHAR(512) NOT NULL COMMENT '面向用户展示的标签名称',
    normalized_key VARCHAR(255) NOT NULL COMMENT '标签规范化键',
    status         VARCHAR(32)  NOT NULL COMMENT '标签字典状态：active 或 inactive',
    created_at     VARCHAR(40)  NOT NULL COMMENT '标签创建时间，ISO-8601 UTC 字符串',
    updated_at     VARCHAR(40)  NOT NULL COMMENT '标签最近更新时间，ISO-8601 UTC 字符串',
    PRIMARY KEY (id),
    UNIQUE KEY uk_tags_space_normalized_key (space_id, normalized_key)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '知识空间标签字典';

CREATE TABLE document_tagging_runs
(
    id                      BIGINT       NOT NULL COMMENT '标签运行唯一标识，由应用侧 Snowflake 生成',
    space_id                BIGINT       NOT NULL COMMENT '所属知识空间标识，业务层校验空间归属',
    source_document_id      BIGINT       NOT NULL COMMENT '本次分析的来源资料标识',
    source_content_hash     CHAR(64)     NOT NULL COMMENT '运行开始时来源资料的内容指纹',
    status                  VARCHAR(32)  NOT NULL COMMENT '运行状态：processing、completed 或 failed',
    failure_stage           VARCHAR(128)          COMMENT '失败阶段',
    error_message           TEXT                  COMMENT '面向用户或运维的稳定错误摘要',
    summary                 TEXT                  COMMENT '通过结构校验的模型摘要',
    chunk_count             INT          NOT NULL DEFAULT 0 COMMENT '提供给标签客户端的分片数量',
    context_character_count INT          NOT NULL DEFAULT 0 COMMENT '提供给标签客户端的分片文本字符总数',
    suggestion_count        INT          NOT NULL DEFAULT 0 COMMENT '本次新写入的 suggested 标签数量',
    evidence_failure_count  INT          NOT NULL DEFAULT 0 COMMENT '因逐字证据失败而未物化的标签候选数量',
    prompt_version          VARCHAR(128) NOT NULL COMMENT '标签 Prompt 版本快照',
    schema_version          VARCHAR(128) NOT NULL COMMENT '标签 Schema 版本快照',
    model_request_count     INT          NOT NULL DEFAULT 0 COMMENT '标签模型请求次数',
    retry_count             INT          NOT NULL DEFAULT 0 COMMENT '标签模型重试次数',
    duration_ms             BIGINT                COMMENT '运行耗时毫秒',
    created_at              VARCHAR(40)  NOT NULL COMMENT '运行创建时间，ISO-8601 UTC 字符串',
    completed_at            VARCHAR(40)           COMMENT '运行完成或失败时间，处理中为空',
    PRIMARY KEY (id),
    KEY idx_document_tagging_runs_space_document_created_at (space_id, source_document_id, created_at DESC, id DESC)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '文档标签抽取运行记录';

CREATE TABLE document_tags
(
    id                 BIGINT       NOT NULL COMMENT '文档标签关系唯一标识，由应用侧 Snowflake 生成',
    space_id           BIGINT       NOT NULL COMMENT '所属知识空间标识，业务层校验空间归属',
    source_document_id BIGINT       NOT NULL COMMENT '被标记的来源资料标识',
    tag_id             BIGINT       NOT NULL COMMENT '空间内标签定义标识',
    source_type        VARCHAR(32)  NOT NULL COMMENT '标签来源：ai、user 或 rule',
    status             VARCHAR(32)  NOT NULL COMMENT '文档标签状态：suggested、confirmed、rejected 或 stale',
    confidence         DOUBLE                COMMENT 'AI 或规则对候选标签的估计置信度；用户手工标签为空',
    extraction_run_id  BIGINT                COMMENT '可选标签抽取运行标识',
    content_hash       CHAR(64)     NOT NULL COMMENT '生成或确认标签时的来源文档内容指纹',
    prompt_version     VARCHAR(128)          COMMENT 'AI 标签 Prompt 版本；用户手工标签为空',
    schema_version     VARCHAR(128)          COMMENT 'AI 标签 Schema 版本；用户手工标签为空',
    document_tag_key   VARCHAR(512) NOT NULL COMMENT '按空间、文档、内容和版本计算的稳定幂等键',
    created_at         VARCHAR(40)  NOT NULL COMMENT '文档标签创建时间，ISO-8601 UTC 字符串',
    updated_at         VARCHAR(40)  NOT NULL COMMENT '文档标签最近更新时间，ISO-8601 UTC 字符串',
    PRIMARY KEY (id),
    UNIQUE KEY uk_document_tags_space_tag_key (space_id, document_tag_key),
    KEY idx_document_tags_space_document_status (space_id, source_document_id, status, updated_at DESC)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '文档标签候选及审核状态';

CREATE TABLE document_tag_evidences
(
    id                 BIGINT       NOT NULL COMMENT '标签证据唯一标识，由应用侧 Snowflake 生成',
    space_id           BIGINT       NOT NULL COMMENT '所属知识空间标识，业务层校验空间归属',
    document_tag_id    BIGINT       NOT NULL COMMENT '被证据支撑的文档标签关系标识',
    source_document_id BIGINT       NOT NULL COMMENT '证据所在来源资料标识',
    chunk_id           VARCHAR(128) NOT NULL COMMENT '当前文档内稳定的分片标识',
    section_path       TEXT         NOT NULL COMMENT '分片所属章节路径',
    quote              TEXT         NOT NULL COMMENT '可逐字反查的原文片段',
    start_offset       INT                   COMMENT '原文起始偏移，半开区间',
    end_offset         INT                   COMMENT '原文结束偏移，不包含该位置字符',
    created_at         VARCHAR(40)  NOT NULL COMMENT '证据创建时间，ISO-8601 UTC 字符串',
    PRIMARY KEY (id),
    KEY idx_document_tag_evidences_document_tag_created_at (space_id, document_tag_id, created_at, id),
    KEY idx_document_tag_evidences_source_document (space_id, source_document_id, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '文档标签逐字证据';

CREATE TABLE document_tag_reviews
(
    id              BIGINT       NOT NULL COMMENT '审核记录唯一标识，由应用侧 Snowflake 生成',
    space_id        BIGINT       NOT NULL COMMENT '所属知识空间标识，业务层校验空间归属',
    document_tag_id BIGINT       NOT NULL COMMENT '被审核的文档标签关系标识',
    action          VARCHAR(32)  NOT NULL COMMENT '审核动作：accept 或 reject',
    reason          TEXT                  COMMENT '采纳说明或拒绝原因',
    operator_name   VARCHAR(512) NOT NULL COMMENT '操作者展示名称',
    created_at      VARCHAR(40)  NOT NULL COMMENT '审核时间，ISO-8601 UTC 字符串',
    PRIMARY KEY (id),
    UNIQUE KEY uk_document_tag_reviews_space_document_tag (space_id, document_tag_id),
    KEY idx_document_tag_reviews_document_tag_created_at (document_tag_id, created_at DESC, id DESC)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '文档标签不可变审核历史';

CREATE TABLE document_association_runs
(
    id                              BIGINT       NOT NULL COMMENT '关联运行唯一标识，由应用侧 Snowflake 生成',
    space_id                        BIGINT       NOT NULL COMMENT '所属知识空间标识，业务层校验空间归属',
    source_document_id              BIGINT       NOT NULL COMMENT '本次作为关联主体的来源资料标识',
    source_content_hash             CHAR(64)     NOT NULL COMMENT '运行开始时主体文档的内容指纹',
    status                          VARCHAR(32)  NOT NULL COMMENT '运行状态：processing、completed 或 failed',
    failure_stage                   VARCHAR(128)          COMMENT '失败阶段',
    error_message                   TEXT                  COMMENT '稳定错误摘要',
    candidate_count                 INT          NOT NULL DEFAULT 0 COMMENT '所有召回通道合并前的候选数量',
    compared_count                  INT          NOT NULL DEFAULT 0 COMMENT '实际交给关联判断模型的候选数量',
    suggestion_count                INT          NOT NULL DEFAULT 0 COMMENT '最终写入的候选关系建议数量',
    tag_candidate_count             INT          NOT NULL DEFAULT 0 COMMENT 'confirmed 标签通道候选数量',
    keyword_candidate_count         INT          NOT NULL DEFAULT 0 COMMENT '关键词、标题和显式引用通道候选数量',
    semantic_candidate_count        INT          NOT NULL DEFAULT 0 COMMENT '语义通道候选数量',
    prompt_version                  VARCHAR(128) NOT NULL COMMENT '关联判断 Prompt 版本',
    schema_version                  VARCHAR(128) NOT NULL COMMENT '关联判断 Schema 版本',
    candidate_recall_policy_version VARCHAR(128) NOT NULL COMMENT '候选召回策略版本',
    association_policy_version      VARCHAR(128) NOT NULL COMMENT '文档关联策略版本',
    embedding_provider              VARCHAR(128)          COMMENT 'Embedding 供应商快照',
    embedding_model                 VARCHAR(128)          COMMENT 'Embedding 模型快照',
    embedding_version               VARCHAR(128)          COMMENT 'Embedding 版本快照',
    top_k                           INT                   COMMENT '召回 TopK',
    similarity_threshold            DOUBLE                COMMENT '召回相似度阈值；未启用向量时为空',
    model_request_count             INT          NOT NULL DEFAULT 0 COMMENT '聊天模型请求次数',
    retry_count                     INT          NOT NULL DEFAULT 0 COMMENT '重试次数',
    duration_ms                     BIGINT                COMMENT '运行耗时毫秒',
    created_at                      VARCHAR(40)  NOT NULL COMMENT '运行创建时间，ISO-8601 UTC 字符串',
    completed_at                    VARCHAR(40)           COMMENT '运行完成或失败时间，处理中为空',
    PRIMARY KEY (id),
    KEY idx_document_association_runs_space_source_created_at (space_id, source_document_id, created_at DESC, id DESC),
    KEY idx_document_association_runs_space_status (space_id, status, created_at DESC)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '文档关联分析运行记录';

CREATE TABLE document_relations
(
    id                         BIGINT       NOT NULL COMMENT '文档关系唯一标识，由应用侧 Snowflake 生成',
    space_id                   BIGINT       NOT NULL COMMENT '所属知识空间标识，业务层校验空间归属',
    source_document_id         BIGINT       NOT NULL COMMENT '关系主体文档标识；对称关系保存规范化左侧文档',
    target_document_id         BIGINT       NOT NULL COMMENT '关系客体文档标识；对称关系保存规范化右侧文档',
    relation_type              VARCHAR(128) NOT NULL COMMENT '固定关系类型：related_to、references、supports、updates 或 conflicts_with',
    direction                  VARCHAR(32)  NOT NULL COMMENT '关系方向：symmetric、current_to_candidate 或 candidate_to_current',
    status                     VARCHAR(32)  NOT NULL COMMENT '关系状态：suggested、confirmed、rejected 或 stale',
    generation_mode            VARCHAR(32)  NOT NULL COMMENT '关系生成方式：explicit_reference、tag_match、keyword_match、semantic_match、hybrid 或 user',
    confidence                 DOUBLE       NOT NULL COMMENT '模型或规则对候选关系的估计置信度，取值范围 0 到 1',
    reason                     TEXT         NOT NULL COMMENT '供人工审核阅读的关系原因',
    association_run_id         BIGINT                COMMENT '产生关系的关联运行标识；手工关系为空',
    source_content_hash        CHAR(64)     NOT NULL COMMENT '关系主体内容指纹快照',
    target_content_hash        CHAR(64)     NOT NULL COMMENT '关系客体内容指纹快照',
    association_policy_version VARCHAR(128) NOT NULL COMMENT '关系策略版本快照',
    relation_key               VARCHAR(512) NOT NULL COMMENT '按空间、端点、内容指纹和版本计算的稳定幂等键',
    created_at                 VARCHAR(40)  NOT NULL COMMENT '关系创建时间，ISO-8601 UTC 字符串',
    updated_at                 VARCHAR(40)  NOT NULL COMMENT '关系最近更新时间，ISO-8601 UTC 字符串',
    PRIMARY KEY (id),
    UNIQUE KEY uk_document_relations_space_relation_key (space_id, relation_key),
    KEY idx_document_relations_space_source_status (space_id, source_document_id, status, updated_at DESC)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '文档之间的候选及确认关系';

CREATE TABLE document_relation_evidences
(
    id                   BIGINT       NOT NULL COMMENT '文档关系证据唯一标识，由应用侧 Snowflake 生成',
    space_id             BIGINT       NOT NULL COMMENT '所属知识空间标识，业务层校验空间归属',
    document_relation_id BIGINT       NOT NULL COMMENT '被证据支撑的文档关系标识',
    source_document_id   BIGINT       NOT NULL COMMENT '证据实际所在的来源资料标识',
    chunk_id             VARCHAR(128) NOT NULL COMMENT '当前文档内稳定的分片标识',
    section_path         TEXT         NOT NULL COMMENT '分片所属章节路径',
    quote                TEXT         NOT NULL COMMENT '可逐字反查的原文片段',
    start_offset         INT                   COMMENT '原文起始偏移，半开区间',
    end_offset           INT                   COMMENT '原文结束偏移，不包含该位置字符',
    evidence_role        VARCHAR(32)  NOT NULL COMMENT '证据角色：source、target 或 cross_reference',
    created_at           VARCHAR(40)  NOT NULL COMMENT '证据创建时间，ISO-8601 UTC 字符串',
    PRIMARY KEY (id),
    KEY idx_document_relation_evidences_relation_created_at (document_relation_id, created_at ASC),
    KEY idx_document_relation_evidences_source_document (space_id, source_document_id, created_at ASC)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '文档关系逐字证据';

CREATE TABLE document_relation_reviews
(
    id                   BIGINT       NOT NULL COMMENT '审核记录唯一标识，由应用侧 Snowflake 生成',
    space_id             BIGINT       NOT NULL COMMENT '所属知识空间标识，业务层校验空间归属',
    document_relation_id BIGINT       NOT NULL COMMENT '被审核的文档关系标识',
    action               VARCHAR(32)  NOT NULL COMMENT '审核动作：accept、reject 或 create',
    reason               TEXT                  COMMENT '拒绝原因、采纳说明或手工创建说明',
    operator_name        VARCHAR(512) NOT NULL COMMENT '操作者展示名称',
    created_at           VARCHAR(40)  NOT NULL COMMENT '审核时间，ISO-8601 UTC 字符串',
    PRIMARY KEY (id),
    KEY idx_document_relation_reviews_relation_created_at (document_relation_id, created_at DESC, id DESC)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '文档关系不可变审核历史';

CREATE TABLE review_actions
(
    id            BIGINT       NOT NULL COMMENT '审核记录唯一标识，由应用侧 Snowflake 生成',
    space_id      BIGINT       NOT NULL COMMENT '所属知识空间标识，业务层校验空间归属',
    edge_id       BIGINT       NOT NULL COMMENT '被审核的图谱关系标识',
    action        VARCHAR(32)  NOT NULL COMMENT '审核动作：accept、reject 或 modify',
    reason        TEXT                  COMMENT '拒绝原因或修改说明',
    operator_name VARCHAR(512) NOT NULL COMMENT '操作者展示名称',
    created_at    VARCHAR(40)  NOT NULL COMMENT '审核时间，ISO-8601 UTC 字符串',
    PRIMARY KEY (id),
    KEY idx_review_actions_edge_created_at (edge_id, created_at DESC, id DESC)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '兼容实体关系审核历史';

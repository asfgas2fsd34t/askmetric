CREATE TABLE evidence_snapshot (
    evidence_snapshot_id text PRIMARY KEY,
    workspace_id text NOT NULL REFERENCES workspace (workspace_id),
    analysis_task_id text NOT NULL REFERENCES analysis_task (analysis_task_id),
    run_id text NOT NULL REFERENCES agent_run (run_id),
    query_id text NOT NULL UNIQUE REFERENCES query_audit (query_id),
    source_table text NOT NULL CHECK (length(source_table) BETWEEN 1 AND 1000),
    source_range text NOT NULL CHECK (length(source_range) BETWEEN 1 AND 500),
    columns_json text NOT NULL CHECK (jsonb_typeof(columns_json::jsonb) = 'array'),
    rows_json text NOT NULL CHECK (jsonb_typeof(rows_json::jsonb) = 'array'),
    row_count integer NOT NULL CHECK (row_count >= 0),
    duration_ms bigint NOT NULL CHECK (duration_ms >= 0),
    snapshot_hash text NOT NULL CHECK (length(snapshot_hash) = 64),
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    UNIQUE (run_id, query_id)
);

COMMENT ON TABLE evidence_snapshot IS '可审计分析实际使用的受治理查询结果快照';
COMMENT ON COLUMN evidence_snapshot.evidence_snapshot_id IS '证据快照全局唯一标识';
COMMENT ON COLUMN evidence_snapshot.workspace_id IS '证据所属工作区';
COMMENT ON COLUMN evidence_snapshot.analysis_task_id IS '证据关联的分析任务';
COMMENT ON COLUMN evidence_snapshot.run_id IS '产生证据的 Agent Run';
COMMENT ON COLUMN evidence_snapshot.query_id IS '产生证据的查询审计记录';
COMMENT ON COLUMN evidence_snapshot.source_table IS '证据使用的来源表，多个表使用逗号分隔';
COMMENT ON COLUMN evidence_snapshot.source_range IS '证据引用的数据范围';
COMMENT ON COLUMN evidence_snapshot.columns_json IS '策略允许的证据列 JSON';
COMMENT ON COLUMN evidence_snapshot.rows_json IS '策略允许的证据行 JSON';
COMMENT ON COLUMN evidence_snapshot.row_count IS '证据行数';
COMMENT ON COLUMN evidence_snapshot.duration_ms IS '查询耗时，单位为毫秒';
COMMENT ON COLUMN evidence_snapshot.snapshot_hash IS '证据内容的 SHA-256 哈希';
COMMENT ON COLUMN evidence_snapshot.created_at IS '证据快照创建时间';

CREATE INDEX evidence_snapshot_conversation_created_idx
    ON evidence_snapshot (workspace_id, created_at DESC, evidence_snapshot_id);
GRANT SELECT, INSERT ON evidence_snapshot TO askmetric_app;

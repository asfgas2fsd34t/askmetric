CREATE TABLE query_audit (
    query_id text PRIMARY KEY,
    workspace_id text NOT NULL REFERENCES workspace (workspace_id),
    user_subject text NOT NULL,
    analysis_task_id text,
    run_id text,
    sql_text text NOT NULL,
    parameter_count integer NOT NULL CHECK (parameter_count >= 0),
    status text NOT NULL CHECK (status IN ('SUCCEEDED', 'REJECTED', 'FAILED')),
    rejection_reason text,
    duration_ms bigint NOT NULL CHECK (duration_ms >= 0),
    row_count integer NOT NULL CHECK (row_count >= 0),
    created_at timestamptz NOT NULL DEFAULT current_timestamp
);

COMMENT ON TABLE query_audit IS 'Java Query Gateway 的查询治理审计记录';
COMMENT ON COLUMN query_audit.query_id IS '查询审计记录标识';
COMMENT ON COLUMN query_audit.workspace_id IS '查询所属工作区';
COMMENT ON COLUMN query_audit.user_subject IS '发起查询的认证主体';
COMMENT ON COLUMN query_audit.analysis_task_id IS '关联分析任务';
COMMENT ON COLUMN query_audit.run_id IS '关联 Agent Run';
COMMENT ON COLUMN query_audit.sql_text IS '原始 SQL 模板';
COMMENT ON COLUMN query_audit.parameter_count IS '参数个数，不保存参数值';
COMMENT ON COLUMN query_audit.status IS '查询治理结果';
COMMENT ON COLUMN query_audit.rejection_reason IS '拒绝或失败原因';
COMMENT ON COLUMN query_audit.duration_ms IS '查询耗时，单位为毫秒';
COMMENT ON COLUMN query_audit.row_count IS '返回行数';
COMMENT ON COLUMN query_audit.created_at IS '审计记录创建时间';

CREATE INDEX query_audit_workspace_created_idx ON query_audit (workspace_id, created_at DESC);
GRANT SELECT, INSERT ON query_audit TO askmetric_app;

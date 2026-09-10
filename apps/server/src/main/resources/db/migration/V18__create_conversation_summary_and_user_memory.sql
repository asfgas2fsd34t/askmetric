CREATE TABLE conversation_summary (
    conversation_summary_id text PRIMARY KEY,
    workspace_id text NOT NULL REFERENCES workspace (workspace_id),
    conversation_id text NOT NULL REFERENCES conversation (conversation_id),
    version integer NOT NULL CHECK (version > 0),
    from_sequence bigint NOT NULL CHECK (from_sequence > 0),
    to_sequence bigint NOT NULL,
    summary_text text NOT NULL CHECK (length(summary_text) BETWEEN 1 AND 4000),
    source_agent_run_id text,
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    UNIQUE (conversation_id, version),
    CHECK (to_sequence >= from_sequence)
);

COMMENT ON TABLE conversation_summary IS '对话的有边界版本化派生摘要；只减少上下文体积，不替代原始 Message';
COMMENT ON COLUMN conversation_summary.conversation_summary_id IS '摘要的稳定标识';
COMMENT ON COLUMN conversation_summary.workspace_id IS '所属工作区';
COMMENT ON COLUMN conversation_summary.conversation_id IS '覆盖的对话';
COMMENT ON COLUMN conversation_summary.version IS '同一对话内的摘要版本，从 1 递增';
COMMENT ON COLUMN conversation_summary.from_sequence IS '覆盖的起始 Message 序号（含）';
COMMENT ON COLUMN conversation_summary.to_sequence IS '覆盖的结束 Message 序号（含）';
COMMENT ON COLUMN conversation_summary.summary_text IS '确定性摘要文本';
COMMENT ON COLUMN conversation_summary.source_agent_run_id IS '触发摘要的 Agent Run；用户手动创建时为空';
COMMENT ON COLUMN conversation_summary.created_at IS '摘要创建时间';

CREATE INDEX conversation_summary_conversation_idx
    ON conversation_summary (conversation_id, version DESC);

CREATE TABLE user_memory (
    user_memory_id text PRIMARY KEY,
    workspace_id text NOT NULL REFERENCES workspace (workspace_id),
    user_subject text NOT NULL,
    content text NOT NULL CHECK (length(content) BETWEEN 1 AND 500),
    status text NOT NULL CHECK (status IN ('PROPOSED', 'CONFIRMED')),
    source_conversation_id text REFERENCES conversation (conversation_id),
    source_agent_run_id text,
    confirmed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    CHECK (status <> 'CONFIRMED' OR confirmed_at IS NOT NULL)
);

COMMENT ON TABLE user_memory IS '经用户明确确认的工作区个人偏好；未确认条目不得进入任何 Agent 上下文';
COMMENT ON COLUMN user_memory.user_memory_id IS '记忆的稳定标识';
COMMENT ON COLUMN user_memory.workspace_id IS '记忆所属工作区；跨工作区不可见';
COMMENT ON COLUMN user_memory.user_subject IS '记忆所有者的认证主体';
COMMENT ON COLUMN user_memory.content IS '偏好内容文本';
COMMENT ON COLUMN user_memory.status IS '确认状态：待确认或已确认';
COMMENT ON COLUMN user_memory.source_conversation_id IS '记忆来源对话；用户直接创建时为空';
COMMENT ON COLUMN user_memory.source_agent_run_id IS '提议记忆的 Agent Run';
COMMENT ON COLUMN user_memory.confirmed_at IS '用户确认时间';
COMMENT ON COLUMN user_memory.created_at IS '创建时间';

CREATE INDEX user_memory_workspace_user_idx
    ON user_memory (workspace_id, user_subject, created_at DESC);

GRANT SELECT, INSERT ON conversation_summary TO askmetric_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON user_memory TO askmetric_app;

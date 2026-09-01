CREATE TABLE agent_run (
    run_id text PRIMARY KEY,
    workspace_id text NOT NULL REFERENCES workspace (workspace_id),
    conversation_id text NOT NULL REFERENCES conversation (conversation_id),
    input_message_id text NOT NULL REFERENCES conversation_message (message_id),
    intent_route text NOT NULL CHECK (intent_route IN ('CHAT', 'ANALYSIS', 'TASK_CONTROL', 'APPROVAL')),
    task_relation text NOT NULL CHECK (task_relation IN ('NONE', 'NEW', 'CONTINUE', 'SWITCH')),
    status text NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    completed_at timestamptz
);

CREATE INDEX agent_run_conversation_created_at_idx
    ON agent_run (conversation_id, created_at, run_id);

CREATE TABLE agent_run_event (
    event_id text PRIMARY KEY,
    run_id text NOT NULL REFERENCES agent_run (run_id),
    sequence bigint NOT NULL CHECK (sequence > 0),
    event_type text NOT NULL CHECK (event_type IN ('ACCEPTED', 'PROGRESS', 'COMPLETED', 'FAILED')),
    occurred_at timestamptz NOT NULL DEFAULT current_timestamp,
    message text NOT NULL CHECK (length(message) BETWEEN 1 AND 4000),
    source text NOT NULL CHECK (source IN ('JAVA', 'PYTHON')),
    UNIQUE (run_id, sequence)
);

GRANT SELECT, INSERT, UPDATE ON agent_run TO askmetric_app;
GRANT SELECT, INSERT ON agent_run_event TO askmetric_app;

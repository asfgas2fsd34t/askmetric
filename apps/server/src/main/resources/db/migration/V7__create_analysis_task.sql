CREATE TABLE analysis_task (
    analysis_task_id text PRIMARY KEY,
    workspace_id text NOT NULL REFERENCES workspace (workspace_id),
    conversation_id text NOT NULL REFERENCES conversation (conversation_id),
    goal text NOT NULL CHECK (length(goal) BETWEEN 1 AND 4000),
    status text NOT NULL CHECK (status IN (
        'ACTIVE', 'WAITING_FOR_INPUT', 'WAITING_FOR_APPROVAL',
        'COMPLETED', 'FAILED', 'CANCELLED'
    )),
    source_agent_run_id text NOT NULL UNIQUE REFERENCES agent_run (run_id),
    created_at timestamptz NOT NULL DEFAULT current_timestamp
);

CREATE INDEX analysis_task_conversation_created_at_idx
    ON analysis_task (conversation_id, created_at, analysis_task_id);

CREATE UNIQUE INDEX analysis_task_one_open_per_conversation_idx
    ON analysis_task (conversation_id)
    WHERE status IN ('ACTIVE', 'WAITING_FOR_INPUT', 'WAITING_FOR_APPROVAL');

ALTER TABLE agent_run
    ADD COLUMN analysis_task_id text REFERENCES analysis_task (analysis_task_id),
    ADD COLUMN intent_confidence numeric(4, 3) NOT NULL DEFAULT 1.000
        CHECK (intent_confidence BETWEEN 0 AND 1);

GRANT SELECT, INSERT, UPDATE ON analysis_task TO askmetric_app;

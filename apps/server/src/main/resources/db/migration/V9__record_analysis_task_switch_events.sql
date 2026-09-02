CREATE TABLE analysis_task_event (
    event_id text PRIMARY KEY,
    analysis_task_id text NOT NULL REFERENCES analysis_task (analysis_task_id),
    source_agent_run_id text NOT NULL REFERENCES agent_run (run_id),
    event_type text NOT NULL CHECK (event_type IN ('SWITCHED')),
    related_analysis_task_id text NOT NULL REFERENCES analysis_task (analysis_task_id),
    occurred_at timestamptz NOT NULL DEFAULT current_timestamp
);

CREATE INDEX analysis_task_event_task_occurred_at_idx
    ON analysis_task_event (analysis_task_id, occurred_at, event_id);

GRANT SELECT, INSERT ON analysis_task_event TO askmetric_app;

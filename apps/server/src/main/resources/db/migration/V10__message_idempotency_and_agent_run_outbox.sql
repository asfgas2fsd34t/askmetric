CREATE TABLE message_idempotency (
    idempotency_id text PRIMARY KEY,
    workspace_id text NOT NULL REFERENCES workspace (workspace_id),
    conversation_id text NOT NULL REFERENCES conversation (conversation_id),
    user_subject text NOT NULL,
    idempotency_key text NOT NULL CHECK (length(idempotency_key) BETWEEN 1 AND 200),
    request_hash text NOT NULL CHECK (length(request_hash) = 64),
    response_json text CHECK (response_json IS NULL OR jsonb_typeof(response_json::jsonb) = 'object'),
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    UNIQUE (workspace_id, conversation_id, user_subject, idempotency_key)
);

CREATE INDEX message_idempotency_created_at_idx
    ON message_idempotency (created_at);

CREATE TABLE agent_run_outbox (
    outbox_id text PRIMARY KEY,
    event_id text NOT NULL UNIQUE,
    run_id text NOT NULL REFERENCES agent_run (run_id),
    topic text NOT NULL CHECK (length(topic) BETWEEN 1 AND 200),
    payload text NOT NULL CHECK (jsonb_typeof(payload::jsonb) = 'object'),
    status text NOT NULL CHECK (status IN ('PENDING', 'PUBLISHING', 'PUBLISHED', 'DEAD_LETTER')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at timestamptz NOT NULL DEFAULT current_timestamp,
    lease_until timestamptz,
    last_error text,
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    published_at timestamptz
);

CREATE INDEX agent_run_outbox_pending_idx
    ON agent_run_outbox (status, next_attempt_at, created_at);

GRANT SELECT, INSERT, UPDATE ON message_idempotency TO askmetric_app;
GRANT SELECT, INSERT, UPDATE ON agent_run_outbox TO askmetric_app;

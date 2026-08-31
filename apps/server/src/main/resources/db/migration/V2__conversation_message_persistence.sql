ALTER TABLE conversation
    ADD COLUMN created_by_subject text,
    ADD COLUMN created_at timestamptz NOT NULL DEFAULT current_timestamp,
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT current_timestamp,
    ADD COLUMN next_message_sequence bigint NOT NULL DEFAULT 1;

CREATE TABLE conversation_message (
    message_id text PRIMARY KEY,
    conversation_id text NOT NULL REFERENCES conversation (conversation_id),
    author text NOT NULL CHECK (author IN ('user', 'assistant', 'system')),
    author_subject text,
    sequence bigint NOT NULL CHECK (sequence > 0),
    content text NOT NULL CHECK (length(content) BETWEEN 1 AND 4000),
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    UNIQUE (conversation_id, sequence)
);

CREATE INDEX conversation_workspace_updated_at_idx
    ON conversation (workspace_id, updated_at DESC);

CREATE INDEX conversation_message_conversation_sequence_idx
    ON conversation_message (conversation_id, sequence);

UPDATE workspace_membership
SET permissions = permissions || ARRAY['VIEW_CONVERSATION', 'CREATE_CONVERSATION', 'CREATE_MESSAGE'];

UPDATE workspace_policy
SET allowed_permissions = allowed_permissions || ARRAY['VIEW_CONVERSATION', 'CREATE_CONVERSATION', 'CREATE_MESSAGE'];

GRANT SELECT, INSERT, UPDATE ON conversation TO askmetric_app;
GRANT SELECT, INSERT ON conversation_message TO askmetric_app;

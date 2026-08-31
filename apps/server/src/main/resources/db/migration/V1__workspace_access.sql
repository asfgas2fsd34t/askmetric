CREATE TABLE workspace (
    workspace_id text PRIMARY KEY,
    workspace_name text NOT NULL
);

CREATE TABLE workspace_membership (
    membership_id text PRIMARY KEY,
    workspace_id text NOT NULL REFERENCES workspace (workspace_id),
    user_subject text NOT NULL,
    permissions text[] NOT NULL,
    UNIQUE (workspace_id, user_subject)
);

CREATE INDEX workspace_membership_user_subject_idx
    ON workspace_membership (user_subject);

CREATE TABLE workspace_policy (
    workspace_id text PRIMARY KEY REFERENCES workspace (workspace_id),
    policy_version integer NOT NULL CHECK (policy_version > 0),
    requires_separate_approver boolean NOT NULL,
    allowed_permissions text[] NOT NULL
);

CREATE TABLE conversation (
    conversation_id text PRIMARY KEY,
    workspace_id text NOT NULL REFERENCES workspace (workspace_id),
    title text NOT NULL
);

INSERT INTO workspace (workspace_id, workspace_name) VALUES
    ('workspace-demo', 'Demo Workspace'),
    ('workspace-growth', 'Growth Workspace'),
    ('workspace-finance', 'Finance Workspace');

INSERT INTO workspace_membership (membership_id, workspace_id, user_subject, permissions) VALUES
    ('membership-demo', 'workspace-demo', '00000000-0000-0000-0000-000000000001',
        ARRAY['VIEW_WORKSPACE', 'CREATE_AGENT_RUN', 'VIEW_AGENT_RUN']),
    ('membership-growth', 'workspace-growth', '00000000-0000-0000-0000-000000000001',
        ARRAY['VIEW_WORKSPACE', 'VIEW_AGENT_RUN']),
    ('membership-finance', 'workspace-finance', '00000000-0000-0000-0000-000000000002',
        ARRAY['VIEW_WORKSPACE', 'CREATE_AGENT_RUN', 'VIEW_AGENT_RUN']);

INSERT INTO workspace_policy (
    workspace_id, policy_version, requires_separate_approver, allowed_permissions
) VALUES
    ('workspace-demo', 1, false, ARRAY['VIEW_WORKSPACE', 'CREATE_AGENT_RUN', 'VIEW_AGENT_RUN']),
    ('workspace-growth', 1, true, ARRAY['VIEW_WORKSPACE', 'CREATE_AGENT_RUN', 'VIEW_AGENT_RUN']),
    ('workspace-finance', 1, true, ARRAY['VIEW_WORKSPACE', 'VIEW_AGENT_RUN']);

INSERT INTO conversation (conversation_id, workspace_id, title) VALUES
    ('conversation-demo', 'workspace-demo', 'Demo Conversation'),
    ('conversation-growth', 'workspace-growth', 'Growth Conversation'),
    ('conversation-finance', 'workspace-finance', 'Finance Conversation');

GRANT USAGE ON SCHEMA public TO askmetric_app;
GRANT SELECT ON workspace, workspace_membership, workspace_policy, conversation TO askmetric_app;

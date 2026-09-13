CREATE TABLE action_proposal (
    action_proposal_id text PRIMARY KEY,
    workspace_id text NOT NULL REFERENCES workspace (workspace_id),
    conversation_id text NOT NULL REFERENCES conversation (conversation_id),
    analysis_task_id text NOT NULL REFERENCES analysis_task (analysis_task_id),
    source_agent_run_id text NOT NULL REFERENCES agent_run (run_id),
    action_type text NOT NULL CHECK (action_type IN ('PROMOTE_CUSTOM_CALIBER', 'REVISE_STANDARD_CALIBER')),
    status text NOT NULL CHECK (status IN ('AWAITING_CONFIRMATION', 'AWAITING_APPROVAL', 'SUPERSEDED', 'DISCARDED')),
    metric_key text NOT NULL,
    version_label text NOT NULL,
    calculation_rule text NOT NULL,
    time_boundary text NOT NULL,
    exclusions text NOT NULL,
    policy_version integer NOT NULL CHECK (policy_version > 0),
    idempotency_key text NOT NULL,
    proposed_by text NOT NULL,
    confirmed_at timestamptz,
    superseded_by text,
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    UNIQUE (workspace_id, idempotency_key),
    CHECK (status <> 'AWAITING_APPROVAL' OR confirmed_at IS NOT NULL),
    CHECK (status <> 'SUPERSEDED' OR superseded_by IS NOT NULL)
);

COMMENT ON TABLE action_proposal IS '等待人工决定、包含确切参数且不可变的特定外部副作用请求；参数只能随新提案出现，永不原地修改';
COMMENT ON COLUMN action_proposal.action_proposal_id IS '提案的稳定标识';
COMMENT ON COLUMN action_proposal.workspace_id IS '提案所属工作区；跨工作区不可见';
COMMENT ON COLUMN action_proposal.conversation_id IS '提案来源对话';
COMMENT ON COLUMN action_proposal.analysis_task_id IS '提案关联的分析任务';
COMMENT ON COLUMN action_proposal.source_agent_run_id IS '提交提案的 Agent Run';
COMMENT ON COLUMN action_proposal.action_type IS '操作类型：升级自定义口径为共享版本或修订标准口径';
COMMENT ON COLUMN action_proposal.status IS '生命周期：待发起者确认、等待审批、已被新提案取代或已放弃';
COMMENT ON COLUMN action_proposal.metric_key IS '口径定义快照：指标键';
COMMENT ON COLUMN action_proposal.version_label IS '口径定义快照：版本标识';
COMMENT ON COLUMN action_proposal.calculation_rule IS '口径定义快照：计算规则';
COMMENT ON COLUMN action_proposal.time_boundary IS '口径定义快照：时间边界';
COMMENT ON COLUMN action_proposal.exclusions IS '口径定义快照：排除项';
COMMENT ON COLUMN action_proposal.policy_version IS '提案创建时的工作区审批策略版本';
COMMENT ON COLUMN action_proposal.idempotency_key IS '提案幂等键；审批执行阶段据此保证同一操作最多生效一次';
COMMENT ON COLUMN action_proposal.proposed_by IS '发起者（运行发起成员）；只有其可以确认或放弃待确认提案';
COMMENT ON COLUMN action_proposal.confirmed_at IS '发起者显式确认提案创建的时间';
COMMENT ON COLUMN action_proposal.superseded_by IS '取代本提案的新提案标识';

CREATE INDEX action_proposal_conversation_idx
    ON action_proposal (conversation_id, created_at DESC);

CREATE INDEX action_proposal_task_active_idx
    ON action_proposal (analysis_task_id)
    WHERE status IN ('AWAITING_CONFIRMATION', 'AWAITING_APPROVAL');

GRANT SELECT, INSERT, UPDATE ON action_proposal TO askmetric_app;

ALTER TABLE action_proposal
    ADD COLUMN decided_by text,
    ADD COLUMN decided_at timestamptz;

COMMENT ON COLUMN action_proposal.decided_by IS '做出批准或拒绝决定的成员；终态提案不可再变更';
COMMENT ON COLUMN action_proposal.decided_at IS '决定时间';

ALTER TABLE action_proposal
    DROP CONSTRAINT action_proposal_status_check;

ALTER TABLE action_proposal
    ADD CONSTRAINT action_proposal_status_check
        CHECK (status IN ('AWAITING_CONFIRMATION', 'AWAITING_APPROVAL', 'SUPERSEDED', 'DISCARDED',
                          'APPROVED', 'REJECTED'));

ALTER TABLE action_proposal
    ADD CONSTRAINT action_proposal_decision_check
        CHECK (status NOT IN ('APPROVED', 'REJECTED') OR (decided_by IS NOT NULL AND decided_at IS NOT NULL));

-- 成员直建的标准口径修订提案不来自任何对话、任务或运行。
COMMENT ON COLUMN action_proposal.conversation_id IS '提案来源对话；成员直建修订提案时为空';
COMMENT ON COLUMN action_proposal.analysis_task_id IS '提案关联的分析任务；成员直建修订提案时为空';
COMMENT ON COLUMN action_proposal.source_agent_run_id IS '提交提案的 Agent Run；成员直建修订提案时为空';

ALTER TABLE action_proposal
    ALTER COLUMN conversation_id DROP NOT NULL,
    ALTER COLUMN analysis_task_id DROP NOT NULL,
    ALTER COLUMN source_agent_run_id DROP NOT NULL;

-- 审批权限：有此权限的成员（管理员/数据分析师）才能批准、拒绝或直建修订提案。
UPDATE workspace_membership
SET permissions = permissions || ARRAY['APPROVE_ACTION_PROPOSAL']
WHERE membership_id IN ('membership-demo', 'membership-growth', 'membership-finance');

UPDATE workspace_policy
SET allowed_permissions = allowed_permissions || ARRAY['APPROVE_ACTION_PROPOSAL']
WHERE workspace_id IN ('workspace-demo', 'workspace-growth', 'workspace-finance');

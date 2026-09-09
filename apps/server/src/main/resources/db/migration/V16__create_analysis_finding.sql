ALTER TABLE agent_run_event
    DROP CONSTRAINT agent_run_event_event_type_check;

ALTER TABLE agent_run_event
    ADD CONSTRAINT agent_run_event_event_type_check
        CHECK (event_type IN (
            'ACCEPTED', 'PROGRESS', 'CLARIFICATION', 'PLAN', 'FINDING',
            'COMPLETED', 'FAILED', 'CANCELLED'));

CREATE TABLE analysis_finding (
    finding_id text PRIMARY KEY,
    workspace_id text NOT NULL REFERENCES workspace (workspace_id),
    analysis_task_id text NOT NULL REFERENCES analysis_task (analysis_task_id),
    run_id text NOT NULL REFERENCES agent_run (run_id),
    metric_definition_version_id text NOT NULL REFERENCES metric_definition_version (metric_definition_version_id),
    verified boolean NOT NULL,
    conclusion text NOT NULL CHECK (length(conclusion) BETWEEN 1 AND 4000),
    evidence_snapshot_ids text NOT NULL,
    assumptions text NOT NULL,
    uncertainties text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT current_timestamp
);

COMMENT ON TABLE analysis_finding IS '由 Agent Run 产出并经 Java 校验的结构化已验证发现';
COMMENT ON COLUMN analysis_finding.finding_id IS '已验证发现的稳定标识';
COMMENT ON COLUMN analysis_finding.workspace_id IS '发现所属工作区';
COMMENT ON COLUMN analysis_finding.analysis_task_id IS '发现关联的分析任务';
COMMENT ON COLUMN analysis_finding.run_id IS '产出该发现的 Agent Run';
COMMENT ON COLUMN analysis_finding.metric_definition_version_id IS '发现引用的指标定义版本';
COMMENT ON COLUMN analysis_finding.verified IS '发现是否通过确定性证据验证';
COMMENT ON COLUMN analysis_finding.conclusion IS '发现的结论文本，未验证时说明证据不足而不伪造结论';
COMMENT ON COLUMN analysis_finding.evidence_snapshot_ids IS '结论依据的 Evidence Snapshot 标识 JSON 数组';
COMMENT ON COLUMN analysis_finding.assumptions IS '发现依赖的假设 JSON 数组';
COMMENT ON COLUMN analysis_finding.uncertainties IS '发现的不确定性说明 JSON 数组';
COMMENT ON COLUMN analysis_finding.created_at IS '发现产生时间';

CREATE INDEX analysis_finding_workspace_created_idx ON analysis_finding (workspace_id, created_at DESC);
CREATE INDEX analysis_finding_conversation_idx ON analysis_finding (analysis_task_id, created_at);

GRANT SELECT, INSERT ON analysis_finding TO askmetric_app;

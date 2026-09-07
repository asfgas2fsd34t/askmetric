CREATE TABLE metric_definition_version (
    metric_definition_version_id text PRIMARY KEY,
    workspace_id text NOT NULL REFERENCES workspace (workspace_id),
    metric_key text NOT NULL CHECK (length(metric_key) BETWEEN 1 AND 100),
    display_name text NOT NULL CHECK (length(display_name) BETWEEN 1 AND 200),
    version_label text NOT NULL CHECK (length(version_label) BETWEEN 1 AND 100),
    definition_source text NOT NULL CHECK (definition_source IN ('STANDARD', 'CUSTOM')),
    calculation_rule text NOT NULL CHECK (length(calculation_rule) BETWEEN 1 AND 4000),
    time_boundary text NOT NULL CHECK (length(time_boundary) BETWEEN 1 AND 2000),
    exclusions text NOT NULL CHECK (length(exclusions) BETWEEN 1 AND 2000),
    based_on_version_id text REFERENCES metric_definition_version (metric_definition_version_id),
    created_by_subject text,
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    UNIQUE (workspace_id, metric_key, version_label)
);

COMMENT ON TABLE metric_definition_version IS '工作区语义目录中不可变的指标定义版本';
COMMENT ON COLUMN metric_definition_version.metric_definition_version_id IS '指标定义版本的全局唯一标识';
COMMENT ON COLUMN metric_definition_version.workspace_id IS '指标定义所属工作区';
COMMENT ON COLUMN metric_definition_version.metric_key IS '指标在语义目录中的稳定键';
COMMENT ON COLUMN metric_definition_version.display_name IS '指标展示名称';
COMMENT ON COLUMN metric_definition_version.version_label IS '用户可见的版本标识';
COMMENT ON COLUMN metric_definition_version.definition_source IS '标准目录或业务用户自定义来源';
COMMENT ON COLUMN metric_definition_version.calculation_rule IS '指标计算规则';
COMMENT ON COLUMN metric_definition_version.time_boundary IS '指标计算使用的时间边界';
COMMENT ON COLUMN metric_definition_version.exclusions IS '指标计算明确排除的项目';
COMMENT ON COLUMN metric_definition_version.based_on_version_id IS '自定义版本所基于的标准版本';
COMMENT ON COLUMN metric_definition_version.created_by_subject IS '创建自定义版本的业务用户身份';
COMMENT ON COLUMN metric_definition_version.created_at IS '指标定义版本创建时间';

CREATE INDEX metric_definition_version_lookup_idx
    ON metric_definition_version (workspace_id, metric_key, created_at DESC);

ALTER TABLE agent_run
    ADD COLUMN metric_definition_version_id text
        REFERENCES metric_definition_version (metric_definition_version_id);

ALTER TABLE analysis_task
    ADD COLUMN metric_definition_version_id text
        REFERENCES metric_definition_version (metric_definition_version_id);

COMMENT ON COLUMN agent_run.metric_definition_version_id IS '本次运行实际采用的指标定义版本';
COMMENT ON COLUMN analysis_task.metric_definition_version_id IS '当前分析任务确认采用的指标定义版本';

GRANT SELECT, INSERT ON metric_definition_version TO askmetric_app;

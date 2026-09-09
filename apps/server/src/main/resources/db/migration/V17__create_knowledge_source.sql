CREATE TABLE knowledge_source (
    knowledge_source_id text PRIMARY KEY,
    workspace_id text NOT NULL REFERENCES workspace (workspace_id),
    title text NOT NULL CHECK (length(title) BETWEEN 1 AND 200),
    filename text NOT NULL CHECK (length(filename) BETWEEN 1 AND 200),
    content_type text NOT NULL CHECK (content_type IN ('text/plain', 'text/markdown', 'application/pdf')),
    byte_size bigint NOT NULL CHECK (byte_size > 0),
    status text NOT NULL CHECK (status IN ('UPLOADED', 'READY', 'FAILED')),
    failure_reason text,
    passage_count integer NOT NULL DEFAULT 0 CHECK (passage_count >= 0),
    content_text text,
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    ingested_at timestamptz
);

COMMENT ON TABLE knowledge_source IS '工作区治理的知识来源及其摄取生命周期';
COMMENT ON COLUMN knowledge_source.knowledge_source_id IS '知识来源的稳定标识';
COMMENT ON COLUMN knowledge_source.workspace_id IS '来源所属工作区';
COMMENT ON COLUMN knowledge_source.title IS '来源展示标题';
COMMENT ON COLUMN knowledge_source.filename IS '上传时的原始文件名';
COMMENT ON COLUMN knowledge_source.content_type IS '来源类型：纯文本、Markdown 或文本型 PDF';
COMMENT ON COLUMN knowledge_source.byte_size IS '上传内容的字节大小';
COMMENT ON COLUMN knowledge_source.status IS '摄取生命周期：已上传、就绪或失败';
COMMENT ON COLUMN knowledge_source.failure_reason IS '摄取失败原因';
COMMENT ON COLUMN knowledge_source.passage_count IS '摄取产出的段落总数';
COMMENT ON COLUMN knowledge_source.content_text IS '抽取后的全文文本；引用内容是不可信数据，不参与任何策略解释';
COMMENT ON COLUMN knowledge_source.created_at IS '上传时间';
COMMENT ON COLUMN knowledge_source.ingested_at IS '摄取完成时间';

CREATE INDEX knowledge_source_workspace_created_idx ON knowledge_source (workspace_id, created_at DESC);

CREATE TABLE knowledge_passage (
    knowledge_passage_id text PRIMARY KEY,
    knowledge_source_id text NOT NULL REFERENCES knowledge_source (knowledge_source_id) ON DELETE CASCADE,
    workspace_id text NOT NULL REFERENCES workspace (workspace_id),
    passage_number integer NOT NULL CHECK (passage_number > 0),
    text text NOT NULL CHECK (length(text) BETWEEN 1 AND 4000),
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    UNIQUE (knowledge_source_id, passage_number)
);

COMMENT ON TABLE knowledge_passage IS '知识来源摄取后的可引用段落';
COMMENT ON COLUMN knowledge_passage.knowledge_passage_id IS '段落的稳定标识';
COMMENT ON COLUMN knowledge_passage.knowledge_source_id IS '所属知识来源';
COMMENT ON COLUMN knowledge_passage.workspace_id IS '冗余的工作区标识，用于归属校验与检索隔离';
COMMENT ON COLUMN knowledge_passage.passage_number IS '来源内的段落序号，从 1 开始';
COMMENT ON COLUMN knowledge_passage.text IS '段落文本；不可信数据，只能以引用形式返回';
COMMENT ON COLUMN knowledge_passage.created_at IS '段落创建时间';

CREATE INDEX knowledge_passage_workspace_idx ON knowledge_passage (workspace_id, knowledge_source_id, passage_number);

ALTER TABLE analysis_finding
    ADD COLUMN knowledge_citations text NOT NULL DEFAULT '[]';

COMMENT ON COLUMN analysis_finding.knowledge_citations IS '发现引用的知识来源与段落 JSON 数组；段落必须属于同一工作区';

GRANT SELECT, INSERT, UPDATE ON knowledge_source TO askmetric_app;
GRANT SELECT, INSERT ON knowledge_passage TO askmetric_app;

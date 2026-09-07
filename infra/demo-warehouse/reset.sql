BEGIN;

-- 演示数据仓库与 AskMetric 业务库使用独立数据库；重置时只替换合成分析数据。
DROP SCHEMA IF EXISTS demo_warehouse CASCADE;
CREATE SCHEMA demo_warehouse;

CREATE TABLE demo_warehouse.dataset_manifest (
    dataset_version text PRIMARY KEY,
    period_start date NOT NULL,
    period_end date NOT NULL,
    currency char(3) NOT NULL,
    CHECK (period_end >= period_start)
);

COMMENT ON TABLE demo_warehouse.dataset_manifest IS '记录确定性演示数据集的版本和固定时间边界';
COMMENT ON COLUMN demo_warehouse.dataset_manifest.dataset_version IS '演示数据集的稳定版本标识';
COMMENT ON COLUMN demo_warehouse.dataset_manifest.period_start IS '演示数据覆盖的起始日期';
COMMENT ON COLUMN demo_warehouse.dataset_manifest.period_end IS '演示数据覆盖的结束日期';
COMMENT ON COLUMN demo_warehouse.dataset_manifest.currency IS 'MRR 金额使用的 ISO 4217 币种';

CREATE TABLE demo_warehouse.customer_segment (
    segment_code text PRIMARY KEY,
    segment_name text NOT NULL UNIQUE,
    display_order smallint NOT NULL UNIQUE CHECK (display_order > 0)
);

COMMENT ON TABLE demo_warehouse.customer_segment IS 'B2B SaaS 客户分层';
COMMENT ON COLUMN demo_warehouse.customer_segment.segment_code IS '客户分层的稳定代码';
COMMENT ON COLUMN demo_warehouse.customer_segment.segment_name IS '客户分层的展示名称';
COMMENT ON COLUMN demo_warehouse.customer_segment.display_order IS '客户分层的固定展示顺序';

CREATE TABLE demo_warehouse.subscription_plan (
    plan_code text PRIMARY KEY,
    plan_name text NOT NULL UNIQUE,
    base_monthly_price_cents integer NOT NULL CHECK (base_monthly_price_cents > 0)
);

COMMENT ON TABLE demo_warehouse.subscription_plan IS '演示订阅套餐及其基础月费';
COMMENT ON COLUMN demo_warehouse.subscription_plan.plan_code IS '订阅套餐的稳定代码';
COMMENT ON COLUMN demo_warehouse.subscription_plan.plan_name IS '订阅套餐的展示名称';
COMMENT ON COLUMN demo_warehouse.subscription_plan.base_monthly_price_cents IS '套餐基础月费，单位为美分';

CREATE TABLE demo_warehouse.customer_account (
    customer_id text PRIMARY KEY,
    account_name text NOT NULL UNIQUE,
    segment_code text NOT NULL REFERENCES demo_warehouse.customer_segment (segment_code),
    created_on date NOT NULL,
    is_synthetic boolean NOT NULL DEFAULT true CHECK (is_synthetic)
);

COMMENT ON TABLE demo_warehouse.customer_account IS '仅用于公开演示的合成 B2B SaaS 客户';
COMMENT ON COLUMN demo_warehouse.customer_account.customer_id IS '合成客户的稳定标识';
COMMENT ON COLUMN demo_warehouse.customer_account.account_name IS '合成客户名称';
COMMENT ON COLUMN demo_warehouse.customer_account.segment_code IS '客户所属分层代码';
COMMENT ON COLUMN demo_warehouse.customer_account.created_on IS '客户创建日期';
COMMENT ON COLUMN demo_warehouse.customer_account.is_synthetic IS '数据是否为合成数据，演示库只允许 true';

CREATE TABLE demo_warehouse.subscription_event (
    event_id text PRIMARY KEY,
    customer_id text NOT NULL REFERENCES demo_warehouse.customer_account (customer_id),
    event_date date NOT NULL,
    event_type text NOT NULL CHECK (event_type IN ('NEW', 'EXPANSION', 'CONTRACTION', 'CHURN')),
    plan_code text NOT NULL REFERENCES demo_warehouse.subscription_plan (plan_code),
    mrr_delta_cents integer NOT NULL CHECK (mrr_delta_cents <> 0),
    is_planted boolean NOT NULL DEFAULT false,
    description text NOT NULL,
    CHECK (event_type NOT IN ('NEW', 'EXPANSION') OR mrr_delta_cents > 0),
    CHECK (event_type NOT IN ('CONTRACTION', 'CHURN') OR mrr_delta_cents < 0)
);

COMMENT ON TABLE demo_warehouse.subscription_event IS '用于重建月末 MRR 的订阅变化事件';
COMMENT ON COLUMN demo_warehouse.subscription_event.event_id IS '订阅事件的稳定标识';
COMMENT ON COLUMN demo_warehouse.subscription_event.customer_id IS '发生订阅变化的客户标识';
COMMENT ON COLUMN demo_warehouse.subscription_event.event_date IS '订阅变化生效日期';
COMMENT ON COLUMN demo_warehouse.subscription_event.event_type IS '订阅变化类型：新增、扩容、缩容或流失';
COMMENT ON COLUMN demo_warehouse.subscription_event.plan_code IS '事件发生时对应的订阅套餐';
COMMENT ON COLUMN demo_warehouse.subscription_event.mrr_delta_cents IS '事件对 MRR 的增减金额，单位为美分';
COMMENT ON COLUMN demo_warehouse.subscription_event.is_planted IS '是否属于已知 MRR 下降植入事件';
COMMENT ON COLUMN demo_warehouse.subscription_event.description IS '供分析核验的事件说明';

INSERT INTO demo_warehouse.dataset_manifest (
    dataset_version, period_start, period_end, currency
) VALUES (
    'mrr-drop-v1', DATE '2025-01-01', DATE '2025-06-30', 'USD'
);

INSERT INTO demo_warehouse.customer_segment (segment_code, segment_name, display_order) VALUES
    ('SMB', 'Small Business', 1),
    ('MID_MARKET', 'Mid-Market', 2),
    ('ENTERPRISE', 'Enterprise', 3);

INSERT INTO demo_warehouse.subscription_plan (plan_code, plan_name, base_monthly_price_cents) VALUES
    ('STARTER', 'Starter', 20000),
    ('GROWTH', 'Growth', 60000),
    ('ENTERPRISE', 'Enterprise', 120000);

INSERT INTO demo_warehouse.customer_account (
    customer_id, account_name, segment_code, created_on
) VALUES
    ('customer-acme', 'Acme Analytics', 'ENTERPRISE', DATE '2024-10-15'),
    ('customer-beacon', 'Beacon Cloud', 'ENTERPRISE', DATE '2024-11-03'),
    ('customer-cobalt', 'Cobalt Labs', 'MID_MARKET', DATE '2024-12-12'),
    ('customer-delta', 'Delta Desk', 'SMB', DATE '2024-12-18'),
    ('customer-echo', 'Echo Systems', 'SMB', DATE '2024-12-21'),
    ('customer-foxtrot', 'Foxtrot Data', 'MID_MARKET', DATE '2025-02-02'),
    ('customer-gamma', 'Gamma Works', 'ENTERPRISE', DATE '2025-06-07');

INSERT INTO demo_warehouse.subscription_event (
    event_id, customer_id, event_date, event_type, plan_code,
    mrr_delta_cents, is_planted, description
) VALUES
    ('event-001', 'customer-acme', DATE '2025-01-01', 'NEW', 'ENTERPRISE', 120000, false,
        'Acme Analytics 开始 Enterprise 订阅'),
    ('event-002', 'customer-beacon', DATE '2025-01-01', 'NEW', 'ENTERPRISE', 120000, false,
        'Beacon Cloud 开始 Enterprise 订阅'),
    ('event-003', 'customer-cobalt', DATE '2025-01-01', 'NEW', 'GROWTH', 60000, false,
        'Cobalt Labs 开始 Growth 订阅'),
    ('event-004', 'customer-delta', DATE '2025-01-01', 'NEW', 'STARTER', 20000, false,
        'Delta Desk 开始 Starter 订阅'),
    ('event-005', 'customer-echo', DATE '2025-01-01', 'NEW', 'STARTER', 20000, false,
        'Echo Systems 开始 Starter 订阅'),
    ('event-006', 'customer-foxtrot', DATE '2025-02-03', 'NEW', 'GROWTH', 60000, false,
        'Foxtrot Data 开始 Growth 订阅'),
    ('event-007', 'customer-cobalt', DATE '2025-03-10', 'EXPANSION', 'GROWTH', 20000, false,
        'Cobalt Labs 增购席位'),
    ('event-008', 'customer-acme', DATE '2025-04-08', 'EXPANSION', 'ENTERPRISE', 30000, false,
        'Acme Analytics 增购 Enterprise 席位'),
    ('event-009', 'customer-beacon', DATE '2025-05-06', 'EXPANSION', 'ENTERPRISE', 30000, false,
        'Beacon Cloud 增购 Enterprise 席位'),
    ('event-010', 'customer-acme', DATE '2025-06-05', 'CHURN', 'ENTERPRISE', -150000, true,
        '已知原因：预算削减导致 Enterprise 客户流失'),
    ('event-011', 'customer-beacon', DATE '2025-06-12', 'CHURN', 'ENTERPRISE', -150000, true,
        '已知原因：并购整合导致 Enterprise 客户流失'),
    ('event-012', 'customer-gamma', DATE '2025-06-08', 'NEW', 'ENTERPRISE', 120000, true,
        '同期新增 Enterprise 客户，部分抵消流失影响');

CREATE VIEW demo_warehouse.monthly_mrr AS
WITH dataset AS (
    SELECT period_start, period_end
    FROM demo_warehouse.dataset_manifest
), months AS (
    SELECT generate_series(
        date_trunc('month', period_start),
        date_trunc('month', period_end),
        INTERVAL '1 month'
    )::date AS month_start
    FROM dataset
)
SELECT
    months.month_start,
    COALESCE(sum(events.mrr_delta_cents), 0)::bigint AS ending_mrr_cents
FROM months
LEFT JOIN demo_warehouse.subscription_event events
    ON events.event_date < months.month_start + INTERVAL '1 month'
GROUP BY months.month_start
ORDER BY months.month_start;

COMMENT ON VIEW demo_warehouse.monthly_mrr IS '按固定数据范围重建的月末 MRR';
COMMENT ON COLUMN demo_warehouse.monthly_mrr.month_start IS 'MRR 所属月份的首日';
COMMENT ON COLUMN demo_warehouse.monthly_mrr.ending_mrr_cents IS '截至该月末的 MRR，单位为美分';

COMMIT;

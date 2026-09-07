#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
compose=(docker compose -f "$repo_dir/infra/docker-compose.yml")
psql=("${compose[@]}" exec -T demo-warehouse psql -X -v ON_ERROR_STOP=1 -U askmetric_demo -d askmetric_demo_warehouse)

"$repo_dir/scripts/reset-demo-warehouse.sh"

dataset_facts="$("${psql[@]}" -At -F '|' <<'SQL'
SELECT dataset_version, period_start, period_end, currency
FROM demo_warehouse.dataset_manifest;

SELECT
    (SELECT count(*) FROM demo_warehouse.customer_segment),
    (SELECT count(*) FROM demo_warehouse.subscription_plan),
    (SELECT count(*) FROM demo_warehouse.customer_account),
    (SELECT count(*) FROM demo_warehouse.subscription_event WHERE event_type = 'CHURN'),
    (SELECT count(*) FROM demo_warehouse.subscription_event WHERE is_planted);

SELECT string_agg(segment_code || ':' || segment_name, ',' ORDER BY display_order)
FROM demo_warehouse.customer_segment;

SELECT string_agg(plan_code || ':' || base_monthly_price_cents, ',' ORDER BY base_monthly_price_cents)
FROM demo_warehouse.subscription_plan;

SELECT
    current_database(),
    to_regclass('public.workspace') IS NULL,
    (SELECT bool_and(is_synthetic) FROM demo_warehouse.customer_account),
    (SELECT bool_and(event_date BETWEEN manifest.period_start AND manifest.period_end)
     FROM demo_warehouse.subscription_event
     CROSS JOIN demo_warehouse.dataset_manifest manifest);
SQL
)"

expected_dataset_facts=$'mrr-drop-v1|2025-01-01|2025-06-30|USD\n3|3|7|2|3\nSMB:Small Business,MID_MARKET:Mid-Market,ENTERPRISE:Enterprise\nSTARTER:20000,GROWTH:60000,ENTERPRISE:120000\naskmetric_demo_warehouse|t|t|t'
test "$dataset_facts" = "$expected_dataset_facts"

ground_truth="$("${psql[@]}" -At -F '|' -f /demo-warehouse/verify-mrr-ground-truth.sql)"
expected_ground_truth='2025-06-01|480000|300000|-180000|-37.50|-300000|120000'
test "$ground_truth" = "$expected_ground_truth"

snapshot() {
  "${psql[@]}" -At <<'SQL'
SELECT row_data
FROM (
    SELECT concat_ws('|', 'manifest', dataset_version, period_start, period_end, currency) AS row_data
    FROM demo_warehouse.dataset_manifest
    UNION ALL
    SELECT concat_ws('|', 'segment', segment_code, segment_name, display_order)
    FROM demo_warehouse.customer_segment
    UNION ALL
    SELECT concat_ws('|', 'plan', plan_code, plan_name, base_monthly_price_cents)
    FROM demo_warehouse.subscription_plan
    UNION ALL
    SELECT concat_ws('|', 'account', customer_id, account_name, segment_code, created_on) AS row_data
    FROM demo_warehouse.customer_account
    UNION ALL
    SELECT concat_ws('|', 'event', event_id, customer_id, event_date, event_type, plan_code,
                     mrr_delta_cents, is_planted, description)
    FROM demo_warehouse.subscription_event
    UNION ALL
    SELECT concat_ws('|', 'monthly_mrr', month_start, ending_mrr_cents)
    FROM demo_warehouse.monthly_mrr
) snapshot
ORDER BY row_data;
SQL
}

first_snapshot="$(snapshot)"
"$repo_dir/scripts/reset-demo-warehouse.sh"
second_snapshot="$(snapshot)"
test "$first_snapshot" = "$second_snapshot"

printf '演示数据仓库冒烟检查通过：MRR 下降 %s 美分，重复重置结果一致\n' '-180000'

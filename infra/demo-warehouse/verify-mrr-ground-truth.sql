-- 受控验证入口：复现 2025 年 6 月 MRR 下降及其植入事件贡献。
WITH monthly AS (
    SELECT
        max(ending_mrr_cents) FILTER (WHERE month_start = DATE '2025-05-01') AS previous_mrr_cents,
        max(ending_mrr_cents) FILTER (WHERE month_start = DATE '2025-06-01') AS current_mrr_cents
    FROM demo_warehouse.monthly_mrr
), planted AS (
    SELECT
        sum(mrr_delta_cents) FILTER (WHERE event_type = 'CHURN') AS gross_churn_cents,
        sum(mrr_delta_cents) FILTER (WHERE event_type = 'NEW') AS new_mrr_cents
    FROM demo_warehouse.subscription_event
    WHERE is_planted
)
SELECT
    DATE '2025-06-01' AS observation_month,
    monthly.previous_mrr_cents,
    monthly.current_mrr_cents,
    monthly.current_mrr_cents - monthly.previous_mrr_cents AS mrr_change_cents,
    round(
        (monthly.current_mrr_cents - monthly.previous_mrr_cents) * 100.0
            / monthly.previous_mrr_cents,
        2
    ) AS mrr_change_percent,
    planted.gross_churn_cents,
    planted.new_mrr_cents
FROM monthly
CROSS JOIN planted;

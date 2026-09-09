"""MRR 参考场景的确定性下钻与已验证发现推导。

结论完全从 Java Query Gateway 返回的证据行推导：
已知植入事件的结论必须与 Demo Warehouse 的确定性 Ground Truth 一致，
证据不足时产出未验证的发现，而不是伪造结论。
"""

# 步骤 1：月度 MRR 基线（monthly_mrr 视图由订阅事件累计重建）。
BASELINE_SQL = (
    "select month_start, ending_mrr_cents from demo_warehouse.monthly_mrr order by month_start"
)

# 步骤 2-3：6 月订阅事件按客户分层、套餐下钻（限定列名，经 Java SQL AST 校验）。
JUNE_EVENTS_SQL = (
    "select segment.segment_code, event.plan_code, event.event_type, event.mrr_delta_cents "
    "from demo_warehouse.subscription_event event "
    "join demo_warehouse.customer_account account on account.customer_id = event.customer_id "
    "join demo_warehouse.customer_segment segment on segment.segment_code = account.segment_code "
    "where event.event_date >= '2025-06-01' and event.event_date < '2025-07-01' "
    "order by event.mrr_delta_cents"
)

DRILLDOWN_QUERIES = (BASELINE_SQL, JUNE_EVENTS_SQL)

# 参考场景的知识检索词；命中的段落只作为发现引用，不影响验证结论。
KNOWLEDGE_QUERY = "MRR 下降 流失"

# 下降分析关注的月末窗口；与演示数据集 mrr-drop-v1 的固定时间边界一致。
BASELINE_MONTH = "2025-05-01"
CURRENT_MONTH = "2025-06-01"
# 单一分层套餐贡献至少一半流失总额，才认定为主要下降来源。
DOMINANT_SHARE_THRESHOLD = 0.5

# 显式下钻意图：口径确认轮（使用标准/自定义口径）只展示计划，目标提问轮不会到达 Python。
_DRILLDOWN_KEYWORDS = ("下钻", "拆分", "细分", "继续", "聚焦", "只看")
_CALIBER_CONFIRMATION_PREFIXES = ("使用标准", "使用自定义", "自定义口径")


def is_mrr_run(request: dict) -> bool:
    """任务目标或消息任一提及 MRR 即视为 MRR 场景；确认口径的消息不一定含 MRR 字样。"""
    text = "%s %s" % (request.get("message", ""), request.get("taskGoal", ""))
    return "mrr" in text.lower() or "月度经常性收入" in text


def is_drilldown_run(request: dict) -> bool:
    """判断本次运行是否执行下钻：MRR 任务且消息表达了显式下钻意图。"""
    if not is_mrr_run(request):
        return False
    message = request.get("message", "")
    if message.startswith(_CALIBER_CONFIRMATION_PREFIXES):
        return False
    return any(keyword in message for keyword in _DRILLDOWN_KEYWORDS)


def _month_value(rows: list[dict], month: str):
    for row in rows:
        if row.get("month_start") == month and row.get("ending_mrr_cents") is not None:
            return int(row["ending_mrr_cents"])
    return None


def derive_finding(
    baseline_rows: list[dict],
    june_event_rows: list[dict],
    metric_definition_version_id: str,
    evidence_snapshot_ids: list[str],
) -> dict:
    """从证据行推导结构化发现；verified 只在降幅可与事件对账且单一来源占主导时为 True。"""
    assumptions = [
        "月末 MRR 由 Demo Warehouse 订阅事件累计重建",
        "月度对比基于已确认口径的 MRR 口径版本",
    ]
    base = {
        "metricDefinitionVersionId": metric_definition_version_id,
        "evidenceSnapshotIds": evidence_snapshot_ids,
        "assumptions": assumptions,
        "uncertainties": [],
        "knowledgeCitations": [],
    }
    previous = _month_value(baseline_rows, BASELINE_MONTH)
    current = _month_value(baseline_rows, CURRENT_MONTH)
    if previous is None or current is None:
        return {
            **base,
            "verified": False,
            "conclusion": "现有证据不足以验证主要下降贡献来源：月度 MRR 基线缺少 %s 或 %s 记录，不推测降幅。"
            % (BASELINE_MONTH, CURRENT_MONTH),
            "uncertainties": ["月度 MRR 基线不完整，无法计算降幅"],
        }
    drop = previous - current
    if drop <= 0:
        return {
            **base,
            "verified": False,
            "conclusion": "现有证据显示 %s MRR 相对 %s 未出现下降（%d -> %d），无需归因。"
            % (CURRENT_MONTH, BASELINE_MONTH, previous, current),
            "uncertainties": [],
        }

    churn_by_group: dict[tuple[str, str], int] = {}
    gross_churn = 0
    other_delta = 0
    for row in june_event_rows:
        delta = int(row.get("mrr_delta_cents") or 0)
        if row.get("event_type") == "CHURN":
            gross_churn += delta
            group = (row.get("segment_code") or "未知分层", row.get("plan_code") or "未知套餐")
            churn_by_group[group] = churn_by_group.get(group, 0) + delta
        else:
            other_delta += delta

    if not churn_by_group:
        return {
            **base,
            "verified": False,
            "conclusion": "现有证据不足以验证主要下降贡献来源：%s 未检索到任何流失事件。" % CURRENT_MONTH,
            "uncertainties": ["缺少当月流失事件，无法归因降幅"],
        }

    reconciled = drop == -(gross_churn + other_delta)
    (segment, plan), top_churn = min(churn_by_group.items(), key=lambda item: item[1])
    dominant = abs(top_churn) >= DOMINANT_SHARE_THRESHOLD * abs(gross_churn)
    if not (reconciled and dominant):
        uncertainties = []
        if not reconciled:
            uncertainties.append("月度降幅与当月订阅事件合计不一致，存在未覆盖的数据窗口")
        if not dominant:
            uncertainties.append("没有单一分层套餐贡献至少一半流失总额，主要来源不唯一")
        return {
            **base,
            "verified": False,
            "conclusion": "现有证据不足以唯一验证主要下降贡献来源：降幅 %d 美分无法与证据完全对账或归因。"
            % drop,
            "uncertainties": uncertainties,
        }

    share = abs(top_churn) / abs(gross_churn) * 100
    percent = drop / previous * 100
    return {
        **base,
        "verified": True,
        "conclusion": (
            "已验证：%s MRR 下降 %d 美分（-%.2f%%，%d -> %d），主要贡献来自 %s 分层 %s 套餐客户流失 %d 美分"
            "（占流失总额 %.1f%%），同期其他订阅事件合计 %+d 美分部分抵消。"
            % (CURRENT_MONTH, drop, percent, previous, current, segment, plan, top_churn, share, other_delta)
        ),
        "uncertainties": [],
    }

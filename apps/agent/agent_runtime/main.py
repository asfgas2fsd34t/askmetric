import json
import logging
import os
import signal
import threading
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timezone
from enum import Enum
from pathlib import Path

from agent_runtime.mrr_drilldown import (
    DRILLDOWN_QUERIES,
    derive_finding,
    is_drilldown_run,
    is_mrr_run,
)

from jsonschema import Draft202012Validator, FormatChecker
from rocketmq import (
    ClientConfiguration,
    ConsumeResult,
    Credentials,
    FilterExpression,
    Message,
    MessageListener,
    Producer,
    PushConsumer,
)

LOG = logging.getLogger("askmetric.agent")
REQUEST_TOPIC = os.getenv("ROCKETMQ_REQUEST_TOPIC", "askmetric-agent-run-request")
EVENT_TOPIC = os.getenv("ROCKETMQ_EVENT_TOPIC", "askmetric-agent-run-event")
ENDPOINTS = os.getenv("ROCKETMQ_ENDPOINTS", "proxy:8081")
CONSUMER_GROUP = os.getenv("ROCKETMQ_CONSUMER_GROUP", "askmetric-agent-runtime")
FORMAT_CHECKER = FormatChecker()


class AgentRunEventType(str, Enum):
    """Agent Run 生命周期事件及其 JSON 传输值。"""

    REQUESTED = "agent.run.requested"  # Java 请求 Python 执行 Agent Run。
    CANCEL_REQUESTED = "agent.run.cancel.requested"  # Java 请求 Python 停止 Agent Run。
    ACCEPTED = "agent.run.accepted"  # 运行已进入队列。
    PROGRESS = "agent.run.progress"  # Python 正在处理运行。
    PLAN = "agent.run.plan"  # Python 已生成分析计划并展示下钻步骤。
    FINDING = "agent.run.finding"  # Python 已产出基于 Evidence Snapshot 的已验证发现。
    COMPLETED = "agent.run.completed"  # 运行已产生最终结果。
    FAILED = "agent.run.failed"  # 运行以失败终止。


class AgentRunEventSource(str, Enum):
    """Agent Run 事件生产者及其 JSON 传输值。"""

    PYTHON = "python"  # Python Agent Runtime 产生的事件。


def resolve_contract_root() -> Path:
    configured = os.getenv("ASKMETRIC_CONTRACT_ROOT")
    if configured:
        return Path(configured)
    source = Path(__file__).resolve()
    # 同时支持仓库内本地运行与容器内复制 contracts 后的目录结构，避免依赖工作目录。
    for parent_index in (3, 1):
        if parent_index >= len(source.parents):
            continue
        candidate = source.parents[parent_index] / "contracts" / "events"
        if candidate.is_dir():
            return candidate
    return source.parents[1] / "contracts" / "events"


CONTRACT_ROOT = resolve_contract_root()


def load_validator(filename: str) -> Draft202012Validator:
    with (CONTRACT_ROOT / filename).open(encoding="utf-8") as contract_file:
        return Draft202012Validator(json.load(contract_file), format_checker=FORMAT_CHECKER)


REQUEST_VALIDATOR = load_validator("agent-run-request.v1.json")
EVENT_VALIDATOR = load_validator("agent-run-event.v1.json")


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def event(
    request: dict,
    event_type: AgentRunEventType,
    sequence: int,
    message: str,
    finding: dict | None = None,
) -> dict:
    payload = {
        # 重投同一请求时生成同一个事件 ID，Java 端据此去重，而不是重复写入进度或终态。
        "eventId": str(uuid.uuid5(
            uuid.NAMESPACE_URL,
            f"askmetric:agent-run:{request['eventId']}:{event_type.value}",
        )),
        "schemaVersion": 1,
        "eventType": event_type.value,
        "sequence": sequence,
        "occurredAt": now_iso(),
        "conversationId": request["conversationId"],
        "runId": request["runId"],
        "message": message,
        "source": AgentRunEventSource.PYTHON.value,
    }
    if finding is not None:
        payload["finding"] = finding
    validate_event_payload(payload)
    return payload


def validate_event_payload(payload: dict) -> None:
    errors = sorted(EVENT_VALIDATOR.iter_errors(payload), key=lambda error: error.path)
    if errors:
        raise ValueError(f"Agent Run 事件不符合 Schema: {errors[0].message}")


def validate_request(request: dict) -> None:
    errors = sorted(REQUEST_VALIDATOR.iter_errors(request), key=lambda error: error.path)
    if errors:
        raise ValueError(f"Agent Run 请求不符合 Schema: {errors[0].message}")
    for field in ("eventId", "occurredAt", "conversationId", "runId"):
        if not isinstance(request[field], str) or not request[field].strip():
            raise ValueError(f"Agent Run 请求字段无效: {field}")


def first_event_sequence(request: dict) -> int:
    """Java 在请求中携带 lastEventSequence；Python 从其下一个序号开始连续发号。"""
    return int(request.get("lastEventSequence", 1)) + 1


def server_base_url() -> str:
    return os.getenv("SERVER_BASE_URL", "http://localhost:8080").rstrip("/")


def post_governed_query(base_url: str, query_grant: str, run_id: str, sql: str) -> dict:
    """用运行绑定的查询授权调用 Java Query Gateway；任何非 2xx 都会抛给失败终态处理。"""
    payload = json.dumps({"runId": run_id, "sql": sql}).encode("utf-8")
    outgoing = urllib.request.Request(
        base_url + "/api/v1/agent-run-queries",
        data=payload,
        headers={"Content-Type": "application/json", "X-Query-Grant": query_grant},
        method="POST",
    )
    with urllib.request.urlopen(outgoing, timeout=15) as response:
        return json.loads(response.read().decode("utf-8"))


def retrieve_knowledge(base_url: str, query_grant: str, run_id: str, query: str) -> list[dict]:
    """用同一查询授权检索工作区知识段落；检索结果只作为引用数据，不影响任何决策。"""
    url = (
        base_url + "/api/v1/agent-run-knowledge?runId=" + urllib.parse.quote(run_id)
        + "&q=" + urllib.parse.quote(query)
    )
    outgoing = urllib.request.Request(url, headers={"X-Query-Grant": query_grant}, method="GET")
    with urllib.request.urlopen(outgoing, timeout=10) as response:
        return json.loads(response.read().decode("utf-8"))


def submit_action_proposal(base_url: str, query_grant: str, run_id: str, action_type: str) -> dict:
    """用同一查询授权提交操作提案；确切参数由 Java 从运行绑定的口径定义快照推导。"""
    payload = json.dumps({"runId": run_id, "actionType": action_type}).encode("utf-8")
    outgoing = urllib.request.Request(
        base_url + "/api/v1/agent-run-proposals",
        data=payload,
        headers={"Content-Type": "application/json", "X-Query-Grant": query_grant},
        method="POST",
    )
    with urllib.request.urlopen(outgoing, timeout=15) as response:
        return json.loads(response.read().decode("utf-8"))


def execute_drilldown(request: dict) -> dict:
    """执行受治理下钻：检索证据、推导已验证发现；缺少授权视为运行失败。"""
    query_grant = request.get("queryGrant")
    if not query_grant:
        raise RuntimeError("缺少查询授权，无法执行受治理下钻")
    metric_version = request.get("metricDefinitionVersionId")
    if not metric_version:
        raise RuntimeError("缺少已确认的指标定义版本，无法执行受治理下钻")
    from agent_runtime.mrr_drilldown import DRILLDOWN_QUERIES, KNOWLEDGE_QUERY, derive_finding

    results = [
        post_governed_query(server_base_url(), query_grant, request["runId"], sql)
        for sql in DRILLDOWN_QUERIES
    ]
    finding = derive_finding(
        results[0].get("rows") or [],
        results[1].get("rows") or [],
        metric_version,
        [result["evidenceSnapshotId"] for result in results if result.get("evidenceSnapshotId")],
    )
    # 知识引用是佐证性输入：检索失败只降级为无引用，不影响证据验证结论。
    try:
        items = retrieve_knowledge(server_base_url(), query_grant, request["runId"], KNOWLEDGE_QUERY)
        finding["knowledgeCitations"] = [
            {
                "knowledgeSourceId": item["knowledgeSourceId"],
                "passageNumber": int(item["passageNumber"]),
                "quote": item["quote"],
            }
            for item in items
        ]
    except Exception:
        LOG.warning("知识段落检索失败，发现将以无引用继续", exc_info=True)
    return finding


# MRR 参考场景的固定下钻步骤；口径已由 Java 确认后才进入该计划。
MRR_PLAN_STEPS = (
    "按月汇总已确认口径的 MRR 基线",
    "对比 5 月与 6 月 MRR 变化",
    "按套餐分组下钻降幅来源",
    "按客户分层定位主要贡献客户",
    "验证证据并产出已验证发现",
)

GENERIC_PLAN_STEPS = (
    "理解分析目标与口径",
    "检索相关指标定义与知识来源",
    "执行受治理查询获取证据",
    "验证证据与假设",
    "汇总结论与不确定性",
)


def build_plan_steps(request: dict) -> tuple[str, ...]:
    return MRR_PLAN_STEPS if is_mrr_run(request) else GENERIC_PLAN_STEPS


def plan_message(request: dict) -> str:
    steps = "；".join(
        f"{index}. {step}" for index, step in enumerate(build_plan_steps(request), start=1)
    )
    return f"阶段 planning：分析计划：{steps}"


def completed_message(request: dict) -> str:
    if is_mrr_run(request):
        return "分析计划已展示，等待业务用户确认后开始下钻检索"
    return "分析计划已展示，等待继续推进分析任务"


def _maybe_submit_caliber_proposal(request: dict, finding: dict | None) -> None:
    """自定义口径的已验证发现之后，提议把该口径升级为工作区共享版本（ADR-0009）。

    提案是可选的后续动作：提交失败只降级为无提案，分析结论本身不受影响；
    幂等键由 Java 按（运行、操作类型）确定，重放不会产生第二个提案。
    """
    if not finding or not finding.get("verified"):
        return
    if request.get("metricDefinitionScope") != "CUSTOM":
        return
    query_grant = request.get("queryGrant")
    if not query_grant:
        return
    try:
        submit_action_proposal(
            server_base_url(), query_grant, request["runId"], "PROMOTE_CUSTOM_CALIBER")
    except Exception:
        LOG.warning("操作提案提交失败，本次运行以无提案继续", exc_info=True)


class AgentListener(MessageListener):
    def __init__(self, producer: Producer):
        self.producer = producer
        self._states: dict[str, dict[str, bool]] = {}
        self._state_lock = threading.RLock()

    def consume(self, message: Message) -> ConsumeResult:
        request = None
        try:
            request = json.loads(message.body.decode("utf-8"))
            validate_request(request)
            if request["eventType"] == AgentRunEventType.CANCEL_REQUESTED.value:
                self._cancel_run(request)
            else:
                self._emit_run(request)
            return ConsumeResult.SUCCESS
        except Exception:
            LOG.exception("处理 Agent Run 请求失败")
            if request is None:
                # 无法解析或校验请求时不能构造合规失败事件，交还 RocketMQ 重试/死信处理。
                return ConsumeResult.FAILURE
            try:
                self._emit_failure(request)
                return ConsumeResult.SUCCESS
            except Exception:
                LOG.exception("发送 Agent Run 失败终态失败")
                return ConsumeResult.FAILURE

    def _emit_run(self, request: dict) -> None:
        # RocketMQ 至少一次投递；此状态机保证同一请求在本进程内最多发出一组阶段和终态事件。
        drilldown = is_drilldown_run(request)
        base_sequence = first_event_sequence(request)
        with self._state_lock:
            state = self._states.setdefault(
                request["runId"],
                {"started": False, "finding": False, "completed": False, "failed": False, "cancelled": False},
            )
            if state["failed"] or state["completed"] or state["cancelled"]:
                return
            if not state["started"]:
                self.publish(event(
                    request,
                    AgentRunEventType.PROGRESS if drilldown else AgentRunEventType.PLAN,
                    base_sequence,
                    "阶段 retrieving：通过 Java Query Gateway 检索受治理证据" if drilldown
                    else plan_message(request)))
                # 阶段事件既已投递就视为运行已启动，后续失败可以安全接终态。
                state["started"] = True
        # 受治理查询是网络 I/O，不持有状态锁，取消请求可以被及时观察到。
        finding = execute_drilldown(request) if drilldown else None
        # 查询期间被取消或已失败的运行不再产生任何服务端状态，包括提案草案。
        if drilldown and not state["cancelled"] and not state["failed"]:
            _maybe_submit_caliber_proposal(request, finding)
        with self._state_lock:
            if state["failed"] or state["completed"] or state["cancelled"]:
                return
            if drilldown and not state["finding"]:
                self.publish(event(
                    request, AgentRunEventType.FINDING, base_sequence + 1,
                    finding["conclusion"], finding=finding))
                state["finding"] = True
            if not state["completed"]:
                self.publish(event(
                    request,
                    AgentRunEventType.COMPLETED,
                    base_sequence + 2 if drilldown else base_sequence + 1,
                    "下钻完成：结构化发现已提交并关联证据快照" if drilldown
                    else completed_message(request)))
                state["completed"] = True

    def _cancel_run(self, request: dict) -> None:
        with self._state_lock:
            state = self._states.setdefault(
                request["runId"],
                {"started": False, "finding": False, "completed": False, "failed": False, "cancelled": False},
            )
            if not state["completed"] and not state["failed"]:
                state["cancelled"] = True

    def _emit_failure(self, request: dict) -> None:
        base_sequence = first_event_sequence(request)
        with self._state_lock:
            state = self._states.setdefault(
                request["runId"],
                {"started": False, "finding": False, "completed": False, "failed": False, "cancelled": False},
            )
            if state["failed"] or state["completed"] or state["cancelled"]:
                return
            if not state["started"]:
                # 先确认第一个阶段事件的投递，才能安全地产生下一序号的失败终态。
                raise RuntimeError("阶段事件尚未确认投递，等待 RocketMQ 重投")
            self.publish(event(request, AgentRunEventType.FAILED, base_sequence + 1, "Agent Run 处理失败"))
            state["failed"] = True

    def publish(self, payload: dict) -> None:
        outgoing = Message()
        outgoing.topic = EVENT_TOPIC
        outgoing.tag = "agent-run"
        outgoing.keys = payload["runId"]
        outgoing.body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.producer.send(outgoing)


def main() -> None:
    logging.basicConfig(level=os.getenv("LOG_LEVEL", "INFO"), format="%(asctime)s %(levelname)s %(message)s")
    config = ClientConfiguration(ENDPOINTS, Credentials(), request_timeout=10)
    producer = Producer(config, (EVENT_TOPIC,))
    consumer = PushConsumer(config, CONSUMER_GROUP, AgentListener(producer), {REQUEST_TOPIC: FilterExpression()})
    stopped = threading.Event()

    def shutdown(*_args: object) -> None:
        if stopped.is_set():
            return
        stopped.set()
        LOG.info("关闭 Synthetic Agent Runtime")
        consumer.shutdown()
        producer.shutdown()

    signal.signal(signal.SIGTERM, shutdown)
    signal.signal(signal.SIGINT, shutdown)
    producer.startup()
    consumer.startup()
    LOG.info("Synthetic Agent Runtime 已连接 RocketMQ endpoint=%s", ENDPOINTS)
    stopped.wait()


if __name__ == "__main__":
    main()

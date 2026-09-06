import json
import logging
import os
import signal
import threading
import uuid
from datetime import datetime, timezone
from enum import Enum
from pathlib import Path

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
        with self._state_lock:
            # RocketMQ 至少一次投递；此状态机保证同一请求在本进程内最多发出一组进度和终态。
            state = self._states.setdefault(
                request["runId"],
                {"progress": False, "completed": False, "failed": False, "cancelled": False},
            )
            if state["failed"] or state["completed"] or state["cancelled"]:
                return
            if not state["progress"]:
                self.publish(event(request, AgentRunEventType.PROGRESS, 2, "Python Synthetic Agent 正在处理"))
                state["progress"] = True
        with self._state_lock:
            if state["cancelled"]:
                return
            if not state["completed"]:
                self.publish(event(request, AgentRunEventType.COMPLETED, 3, "Synthetic Agent Run completed"))
                state["completed"] = True

    def _cancel_run(self, request: dict) -> None:
        with self._state_lock:
            state = self._states.setdefault(
                request["runId"],
                {"progress": False, "completed": False, "failed": False, "cancelled": False},
            )
            if not state["completed"] and not state["failed"]:
                state["cancelled"] = True

    def _emit_failure(self, request: dict) -> None:
        with self._state_lock:
            state = self._states.setdefault(
                request["runId"],
                {"progress": False, "completed": False, "failed": False, "cancelled": False},
            )
            if state["failed"] or state["completed"] or state["cancelled"]:
                return
            if not state["progress"]:
                # 先确认序号 2 的进度事件，才能安全地产生序号 3 的失败终态。
                raise RuntimeError("进度事件尚未确认投递，等待 RocketMQ 重投")
            self.publish(event(request, AgentRunEventType.FAILED, 3, "Synthetic Agent Run failed"))
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

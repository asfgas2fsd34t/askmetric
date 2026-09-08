import json
from datetime import datetime, timezone
from pathlib import Path
from types import SimpleNamespace

import pytest
from rocketmq import ConsumeResult

from agent_runtime.main import (
    AgentListener,
    AgentRunEventType,
    event,
    plan_message,
    validate_request,
)


def test_completed_event_preserves_run_identity_and_sequence():
    request = {
        "eventId": "evt-1",
        "runId": "run-1",
        "conversationId": "conv-1",
    }

    payload = event(request, AgentRunEventType.COMPLETED, 3, "done")

    assert payload["schemaVersion"] == 1
    assert payload["sequence"] == 3
    datetime.fromisoformat(payload["occurredAt"].replace("Z", "+00:00"))


def test_contract_files_are_valid_json():
    root = Path(__file__).parents[3]
    for contract in root.glob("contracts/events/*.json"):
        json.loads(contract.read_text())


def test_request_validation_rejects_unknown_properties():
    request = {
        "eventId": "evt-1",
        "schemaVersion": 1,
        "eventType": "agent.run.requested",
        "sequence": 1,
        "occurredAt": "2026-08-28T02:00:00Z",
        "conversationId": "conv-1",
        "runId": "run-1",
        "message": "hello",
        "unexpected": True,
    }

    with pytest.raises(ValueError, match="Schema"):
        validate_request(request)


def test_request_validation_accepts_an_optional_last_event_sequence():
    request = {
        "eventId": "evt-1",
        "schemaVersion": 1,
        "eventType": "agent.run.requested",
        "sequence": 1,
        "occurredAt": "2026-08-28T02:00:00Z",
        "conversationId": "conv-1",
        "runId": "run-1",
        "message": "hello",
        "lastEventSequence": 2,
    }

    validate_request(request)

    invalid = {**request, "lastEventSequence": -1}
    with pytest.raises(ValueError, match="minimum"):
        validate_request(invalid)


def test_listener_publishes_plan_and_terminal_events():
    request = {
        "eventId": "evt-1",
        "schemaVersion": 1,
        "eventType": "agent.run.requested",
        "sequence": 1,
        "occurredAt": "2026-08-28T02:00:00Z",
        "conversationId": "conv-1",
        "runId": "run-1",
        "message": "hello",
    }

    class FakeProducer:
        def __init__(self):
            self.messages = []

        def send(self, message):
            self.messages.append(message)

    producer = FakeProducer()
    result = AgentListener(producer).consume(
        SimpleNamespace(body=json.dumps(request).encode("utf-8"))
    )

    assert result is ConsumeResult.SUCCESS
    payloads = [json.loads(message.body) for message in producer.messages]
    assert [payload["eventType"] for payload in payloads] == [
        "agent.run.plan",
        "agent.run.completed",
    ]
    assert [payload["sequence"] for payload in payloads] == [2, 3]
    assert payloads[0]["message"].startswith("阶段 planning：分析计划：")


def test_listener_plans_mrr_drilldown_steps_after_the_caliber_is_confirmed():
    request = {
        "eventId": "evt-mrr",
        "schemaVersion": 1,
        "eventType": "agent.run.requested",
        "sequence": 1,
        "occurredAt": "2026-08-28T02:00:00Z",
        "conversationId": "conv-1",
        "runId": "run-1",
        "message": "使用标准 MRR v3 口径",
        "lastEventSequence": 2,
    }

    class FakeProducer:
        def __init__(self):
            self.messages = []

        def send(self, message):
            self.messages.append(message)

    producer = FakeProducer()
    result = AgentListener(producer).consume(
        SimpleNamespace(body=json.dumps(request).encode("utf-8"))
    )

    assert result is ConsumeResult.SUCCESS
    payloads = [json.loads(message.body) for message in producer.messages]
    assert [payload["sequence"] for payload in payloads] == [3, 4]
    assert "按套餐分组下钻降幅来源" in payloads[0]["message"]
    assert "按客户分层定位主要贡献客户" in payloads[0]["message"]
    assert payloads[1]["message"] == "分析计划已展示，等待业务用户确认后开始下钻检索"


def test_plan_message_keeps_generic_steps_for_non_mrr_goals():
    payload = plan_message({"message": "分析客户流失趋势", "taskGoal": "调查客户流失率"})

    assert "mrr" not in payload.lower()
    assert "理解分析目标与口径" in payload
    assert "执行受治理查询获取证据" in payload


def test_plan_message_uses_the_task_goal_when_the_message_omits_mrr():
    request = {
        "message": "使用自定义口径：只统计月末仍处于有效状态的付费订阅",
        "taskGoal": "为什么本月 MRR 下降？",
    }

    payload = plan_message(request)

    assert "按套餐分组下钻降幅来源" in payload
    assert "理解分析目标与口径" not in payload


def test_listener_reuses_event_ids_when_a_request_is_retried():
    request = {
        "eventId": "evt-retry",
        "schemaVersion": 1,
        "eventType": "agent.run.requested",
        "sequence": 1,
        "occurredAt": "2026-08-28T02:00:00Z",
        "conversationId": "conv-1",
        "runId": "run-1",
        "message": "hello",
    }

    class FakeProducer:
        def __init__(self):
            self.messages = []

        def send(self, message):
            self.messages.append(message)

    producer = FakeProducer()
    listener = AgentListener(producer)
    message = SimpleNamespace(body=json.dumps(request).encode("utf-8"))

    assert listener.consume(message) is ConsumeResult.SUCCESS
    assert listener.consume(message) is ConsumeResult.SUCCESS
    assert len(producer.messages) == 2
    assert len({json.loads(message.body)["eventId"] for message in producer.messages}) == 2


def test_listener_publishes_a_failed_terminal_event_when_processing_fails():
    request = {
        "eventId": "evt-failure",
        "schemaVersion": 1,
        "eventType": "agent.run.requested",
        "sequence": 1,
        "occurredAt": "2026-08-28T02:00:00Z",
        "conversationId": "conv-1",
        "runId": "run-1",
        "message": "hello",
    }

    class FailOnceProducer:
        def __init__(self):
            self.messages = []
            self.send_count = 0

        def send(self, message):
            self.send_count += 1
            if self.send_count == 2:
                raise RuntimeError("broker unavailable")
            self.messages.append(message)

    producer = FailOnceProducer()
    result = AgentListener(producer).consume(
        SimpleNamespace(body=json.dumps(request).encode("utf-8"))
    )

    assert result is ConsumeResult.SUCCESS
    assert [json.loads(message.body)["eventType"] for message in producer.messages] == [
        "agent.run.plan",
        "agent.run.failed",
    ]


def test_listener_retries_when_plan_delivery_is_not_confirmed():
    request = {
        "eventId": "evt-plan-retry",
        "schemaVersion": 1,
        "eventType": "agent.run.requested",
        "sequence": 1,
        "occurredAt": "2026-08-28T02:00:00Z",
        "conversationId": "conv-1",
        "runId": "run-1",
        "message": "hello",
    }

    class FailFirstProducer:
        def __init__(self):
            self.messages = []
            self.send_count = 0

        def send(self, message):
            self.send_count += 1
            if self.send_count == 1:
                raise RuntimeError("broker unavailable")
            self.messages.append(message)

    producer = FailFirstProducer()
    listener = AgentListener(producer)
    message = SimpleNamespace(body=json.dumps(request).encode("utf-8"))

    assert listener.consume(message) is ConsumeResult.FAILURE
    assert listener.consume(message) is ConsumeResult.SUCCESS
    assert [json.loads(message.body)["eventType"] for message in producer.messages] == [
        "agent.run.plan",
        "agent.run.completed",
    ]


def test_listener_does_not_complete_after_receiving_cancellation():
    run_request = {
        "eventId": "evt-run",
        "schemaVersion": 1,
        "eventType": "agent.run.requested",
        "sequence": 1,
        "occurredAt": "2026-09-06T02:00:00Z",
        "conversationId": "conv-1",
        "runId": "run-1",
        "message": "analyse MRR",
    }
    cancel_request = {
        **run_request,
        "eventId": "evt-cancel",
        "eventType": "agent.run.cancel.requested",
        "sequence": 2,
        "message": "用户主动停止分析",
    }

    class CancelAfterPlanProducer:
        def __init__(self):
            self.messages = []
            self.listener = None

        def send(self, message):
            self.messages.append(message)
            if json.loads(message.body)["eventType"] == "agent.run.plan":
                self.listener.consume(SimpleNamespace(body=json.dumps(cancel_request).encode("utf-8")))

    producer = CancelAfterPlanProducer()
    listener = AgentListener(producer)
    producer.listener = listener

    assert listener.consume(SimpleNamespace(body=json.dumps(run_request).encode("utf-8"))) is ConsumeResult.SUCCESS
    assert [json.loads(message.body)["eventType"] for message in producer.messages] == ["agent.run.plan"]

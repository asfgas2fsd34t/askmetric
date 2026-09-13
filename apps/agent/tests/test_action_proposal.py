"""T22 验收：自定义口径的已验证发现之后，Agent 提交操作提案草案；
标准口径、缺失作用域或未验证发现不提议，提案提交失败不拖垮分析运行。"""
import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from types import SimpleNamespace

import pytest
from rocketmq import ConsumeResult

from agent_runtime.main import AgentListener
from test_mrr_drilldown import (
    _QueryGatewayStub,
    _drilldown_request,
)


@pytest.fixture(autouse=True)
def _resetGatewayStubState():
    """桩状态是类属性；进入与退出都清空，避免污染其他测试文件的断言。"""
    _QueryGatewayStub.seen_grants = []
    _QueryGatewayStub.knowledge_items = []
    _ProposalRecordingStub.proposals = []
    _ProposalRecordingStub.proposal_status = 200
    yield
    _QueryGatewayStub.seen_grants = []
    _QueryGatewayStub.knowledge_items = []
    _ProposalRecordingStub.proposals = []


class _ProposalRecordingStub(_QueryGatewayStub):
    """在查询网关桩之上记录操作提案提交。"""

    proposals = []
    proposal_status = 200

    def do_POST(self):
        if self.path != "/api/v1/agent-run-proposals":
            super().do_POST()
            return
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        _ProposalRecordingStub.proposals.append(
            {"grant": self.headers.get("X-Query-Grant"), "body": body})
        encoded = json.dumps({"actionProposalId": "action_proposal_1"}).encode("utf-8")
        self.send_response(_ProposalRecordingStub.proposal_status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)


def _start_proposal_stub():
    server = HTTPServer(("127.0.0.1", 0), _ProposalRecordingStub)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server


def _consume(request):
    class FakeProducer:
        def __init__(self):
            self.messages = []

        def send(self, message):
            self.messages.append(message)

    producer = FakeProducer()
    result = AgentListener(producer).consume(
        SimpleNamespace(body=json.dumps(request).encode("utf-8")))
    return result, [json.loads(message.body) for message in producer.messages]


def test_submitsACaliberProposalAfterAVerifiedCustomCaliberFinding(monkeypatch):
    server = _start_proposal_stub()
    monkeypatch.setenv("SERVER_BASE_URL", "http://127.0.0.1:%d" % server.server_address[1])
    request = _drilldown_request()
    request["metricDefinitionScope"] = "CUSTOM"

    result, payloads = _consume(request)

    assert result is ConsumeResult.SUCCESS
    assert [payload["eventType"] for payload in payloads] == [
        "agent.run.progress",
        "agent.run.finding",
        "agent.run.completed",
    ]
    assert _ProposalRecordingStub.proposals == [
        {"grant": "grant-for-run-drilldown", "body": {
            "runId": "run-drilldown",
            "actionType": "PROMOTE_CUSTOM_CALIBER",
        }},
    ]
    server.shutdown()


def test_neverProposesForStandardCaliberOrUnverifiedFindings(monkeypatch):
    server = _start_proposal_stub()
    monkeypatch.setenv("SERVER_BASE_URL", "http://127.0.0.1:%d" % server.server_address[1])

    # 标准口径：不提议。
    result, payloads = _consume(_drilldown_request())
    assert result is ConsumeResult.SUCCESS
    assert payloads[-1]["eventType"] == "agent.run.completed"
    assert _ProposalRecordingStub.proposals == []

    # 未携带口径作用域：不提议。
    request = _drilldown_request(run_id="run-no-scope")
    request.pop("metricDefinitionScope", None)
    _consume(request)
    assert _ProposalRecordingStub.proposals == []
    server.shutdown()


def test_proposalFailureDegradesToACompletedRunWithoutAProposal(monkeypatch):
    _ProposalRecordingStub.proposal_status = 500
    server = _start_proposal_stub()
    monkeypatch.setenv("SERVER_BASE_URL", "http://127.0.0.1:%d" % server.server_address[1])
    request = _drilldown_request()
    request["metricDefinitionScope"] = "CUSTOM"

    result, payloads = _consume(request)

    assert result is ConsumeResult.SUCCESS
    # 提案失败只降级为无提案，已验证发现与终态照常产出。
    assert [payload["eventType"] for payload in payloads] == [
        "agent.run.progress",
        "agent.run.finding",
        "agent.run.completed",
    ]
    assert payloads[1]["finding"]["verified"] is True
    assert _ProposalRecordingStub.proposals
    server.shutdown()

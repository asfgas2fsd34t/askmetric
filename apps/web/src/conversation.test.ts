import { afterEach, describe, expect, it, vi } from "vitest";

import { cancelAgentRun, createMessage, loadConversation, loadConversations, subscribeToAgentRun, watchAgentRun } from "./conversation";

describe("Conversation persistence client", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("reloads Conversation summaries and the selected Java snapshot", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse([{ conversationId: "conversation-1", title: "MRR", messageCount: 1 }]))
      .mockResolvedValueOnce(
        jsonResponse({
          conversationId: "conversation-1",
          workspaceId: "workspace-demo",
          title: "MRR",
          messages: [{ messageId: "message-1", sequence: 1, content: "Why did MRR fall?" }],
        }),
      );
    vi.stubGlobal("fetch", fetchMock);

    const conversations = await loadConversations("token", "workspace-demo");
    const snapshot = await loadConversation("token", "workspace-demo", "conversation-1");

    expect(conversations[0].conversationId).toBe("conversation-1");
    expect(snapshot.messages[0].content).toBe("Why did MRR fall?");
    expect(request(fetchMock, 0).url.endsWith("/api/v1/conversations")).toBe(true);
    expect(request(fetchMock, 1).url.endsWith("/api/v1/conversations/conversation-1")).toBe(true);
    expect(request(fetchMock, 1).headers.get("X-Workspace-Id")).toBe("workspace-demo");
  });

  it("submits a user Message to its Conversation", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({
        userMessage: {
          messageId: "message-2",
          conversationId: "conversation-1",
          author: "user",
          sequence: 2,
          content: "Use calendar month",
          createdAt: "2026-08-31T12:00:00Z",
        },
        assistantMessage: {
          messageId: "message-3",
          conversationId: "conversation-1",
          author: "assistant",
          sequence: 3,
          content: "我是 AskMetric。你可以描述业务问题、指标口径或希望调查的分析目标。",
          createdAt: "2026-08-31T12:00:01Z",
        },
        agentRun: {
          runId: "run-1",
          conversationId: "conversation-1",
          inputMessageId: "message-2",
          intentRoute: "chat",
          createdAt: "2026-08-31T12:00:00Z",
          auditEvents: [],
        },
      }),
    );
    vi.stubGlobal("fetch", fetchMock);

    const accepted = await createMessage(
      "token",
      "workspace-demo",
      "conversation-1",
      "Use calendar month",
      "message-retry-001",
    );

    const submitted = request(fetchMock, 0);
    expect(submitted.method).toBe("POST");
    expect(await submitted.json()).toEqual({ content: "Use calendar month" });
    expect(submitted.headers.get("Idempotency-Key")).toBe("message-retry-001");
    expect(accepted.userMessage.sequence).toBe(2);
    expect(accepted.agentRun.intentRoute).toBe("chat");
  });

  it("returns the new Analysis Task for an MRR question", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({
        userMessage: {
          messageId: "message-4",
          conversationId: "conversation-1",
          author: "user",
          sequence: 4,
          content: "Why did MRR fall?",
          createdAt: "2026-09-01T12:00:00Z",
        },
        agentRun: {
          runId: "run-2",
          conversationId: "conversation-1",
          inputMessageId: "message-4",
          intentRoute: "analysis",
          intentConfidence: 0.95,
          analysisTaskId: "analysis-task-1",
          createdAt: "2026-09-01T12:00:00Z",
          auditEvents: [],
        },
        analysisTask: {
          analysisTaskId: "analysis-task-1",
          conversationId: "conversation-1",
          goal: "Why did MRR fall?",
          status: "active",
          sourceAgentRunId: "run-2",
          createdAt: "2026-09-01T12:00:00Z",
        },
      }),
    );
    vi.stubGlobal("fetch", fetchMock);

    const accepted = await createMessage("token", "workspace-demo", "conversation-1", "Why did MRR fall?");

    expect(accepted.agentRun.intentRoute).toBe("analysis");
    expect(accepted.analysisTask?.sourceAgentRunId).toBe("run-2");
  });

  it("cancels an active Agent Run in its Conversation", async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({
      eventId: "event-cancelled",
      schemaVersion: 1,
      eventType: "agent.run.cancelled",
      sequence: 3,
      occurredAt: "2026-09-06T02:00:00Z",
      conversationId: "conversation-1",
      runId: "run-1",
      message: "分析已取消",
      source: "java",
    }));
    vi.stubGlobal("fetch", fetchMock);

    const cancelled = await cancelAgentRun("token", "workspace-demo", "conversation-1", "run-1");

    const submitted = request(fetchMock, 0);
    expect(submitted.method).toBe("POST");
    expect(submitted.url.endsWith("/api/v1/conversations/conversation-1/runs/run-1/cancel")).toBe(true);
    expect(submitted.headers.get("Authorization")).toBe("Bearer token");
    expect(submitted.headers.get("X-Workspace-Id")).toBe("workspace-demo");
    expect(cancelled.eventType).toBe("agent.run.cancelled");
  });

  it("subscribes to a run from the last received sequence", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        "id: 2\nevent: agent.run.progress\ndata: {\"eventId\":\"event-2\",\"schemaVersion\":1,\"eventType\":\"agent.run.progress\",\"sequence\":2,\"occurredAt\":\"2026-09-02T12:00:00Z\",\"conversationId\":\"conversation-1\",\"runId\":\"run-1\",\"message\":\"running\",\"source\":\"python\"}\n\nid: 3\nevent: agent.run.completed\ndata: {\"eventId\":\"event-3\",\"schemaVersion\":1,\"eventType\":\"agent.run.completed\",\"sequence\":3,\"occurredAt\":\"2026-09-02T12:00:01Z\",\"conversationId\":\"conversation-1\",\"runId\":\"run-1\",\"message\":\"done\",\"source\":\"python\"}\n\n",
        { headers: { "Content-Type": "text/event-stream" } },
      ),
    );
    vi.stubGlobal("fetch", fetchMock);
    const received: string[] = [];

    await subscribeToAgentRun("token", "workspace-demo", "conversation-1", "run-1", 1, (event) => {
      received.push(event.message);
    });

    const subscription = fetchMock.mock.calls[0][1] as RequestInit;
    const requestHeaders = new Headers(subscription.headers);
    expect(requestHeaders.get("Authorization")).toBe("Bearer token");
    expect(requestHeaders.get("X-Workspace-Id")).toBe("workspace-demo");
    expect(requestHeaders.get("Last-Event-ID")).toBe("1");
    expect(received).toEqual(["running", "done"]);
  });

  it("reconnects after a non-terminal stream closes without repeating an event", async () => {
    const progress = "id: 2\nevent: agent.run.progress\ndata: {\"eventId\":\"event-2\",\"schemaVersion\":1,\"eventType\":\"agent.run.progress\",\"sequence\":2,\"occurredAt\":\"2026-09-02T12:00:00Z\",\"conversationId\":\"conversation-1\",\"runId\":\"run-1\",\"message\":\"running\",\"source\":\"python\"}\n\n";
    const completed = "id: 3\nevent: agent.run.completed\ndata: {\"eventId\":\"event-3\",\"schemaVersion\":1,\"eventType\":\"agent.run.completed\",\"sequence\":3,\"occurredAt\":\"2026-09-02T12:00:01Z\",\"conversationId\":\"conversation-1\",\"runId\":\"run-1\",\"message\":\"done\",\"source\":\"python\"}\n\n";
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(new Response(progress, { headers: { "Content-Type": "text/event-stream" } }))
      .mockResolvedValueOnce(new Response(completed, { headers: { "Content-Type": "text/event-stream" } }));
    vi.stubGlobal("fetch", fetchMock);
    const received: number[] = [];

    const stop = watchAgentRun("token", "workspace-demo", "conversation-1", "run-1", 1, (event) => {
      received.push(event.sequence);
    });
    await vi.waitFor(() => expect(received).toEqual([2, 3]));
    stop();

    const reconnected = fetchMock.mock.calls[1][1] as RequestInit;
    expect(new Headers(reconnected.headers).get("Last-Event-ID")).toBe("2");
  });

  it("stops reconnecting after a cancelled event", async () => {
    const cancelled = "id: 3\nevent: agent.run.cancelled\ndata: {\"eventId\":\"event-3\",\"schemaVersion\":1,\"eventType\":\"agent.run.cancelled\",\"sequence\":3,\"occurredAt\":\"2026-09-06T12:00:00Z\",\"conversationId\":\"conversation-1\",\"runId\":\"run-1\",\"message\":\"cancelled\",\"source\":\"java\"}\n\n";
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(cancelled, { headers: { "Content-Type": "text/event-stream" } }),
    );
    vi.stubGlobal("fetch", fetchMock);
    const received: string[] = [];

    const stop = watchAgentRun("token", "workspace-demo", "conversation-1", "run-1", 2, (event) => {
      received.push(event.eventType);
    });
    await vi.waitFor(() => expect(received).toEqual(["agent.run.cancelled"]));
    await new Promise((resolve) => setTimeout(resolve, 10));
    stop();

    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});

function jsonResponse(value: unknown) {
  return new Response(JSON.stringify(value), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

function request(fetchMock: ReturnType<typeof vi.fn>, index: number): Request {
  return fetchMock.mock.calls[index][0] as Request;
}

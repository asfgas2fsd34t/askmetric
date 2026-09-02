import { afterEach, describe, expect, it, vi } from "vitest";

import { createMessage, loadConversation, loadConversations } from "./conversation";

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

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
        messageId: "message-2",
        conversationId: "conversation-1",
        author: "user",
        sequence: 2,
        content: "Use calendar month",
        createdAt: "2026-08-31T12:00:00Z",
      }),
    );
    vi.stubGlobal("fetch", fetchMock);

    const message = await createMessage(
      "token",
      "workspace-demo",
      "conversation-1",
      "Use calendar month",
    );

    const submitted = request(fetchMock, 0);
    expect(submitted.method).toBe("POST");
    expect(await submitted.json()).toEqual({ content: "Use calendar month" });
    expect(message.sequence).toBe(2);
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

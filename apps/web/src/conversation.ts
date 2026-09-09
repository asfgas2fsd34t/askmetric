import createClient from "openapi-fetch";

import type { components, paths } from "./generated/api";

export type ConversationSummary = components["schemas"]["ConversationSummary"];
export type ConversationSnapshot = components["schemas"]["ConversationSnapshot"];
export type ConversationMessage = components["schemas"]["ConversationMessage"];
export type MessageProcessed = components["schemas"]["MessageProcessed"];
export type AgentRunEvent = components["schemas"]["AgentRunEvent"];
export type AgentRun = components["schemas"]["AgentRun"];
export type AgentRunAuditEvent = components["schemas"]["AgentRunAuditEvent"];
export type AnalysisTask = components["schemas"]["AnalysisTask"];
export type AnalysisFinding = components["schemas"]["AnalysisFinding"];
export type EvidenceSnapshot = components["schemas"]["EvidenceSnapshot"];

function client() {
  return createClient<paths>({ baseUrl: globalThis.location?.origin ?? "http://localhost" });
}

function headers(accessToken: string) {
  return { Authorization: `Bearer ${accessToken}` };
}

export async function loadConversations(
  accessToken: string,
  workspaceId: string,
): Promise<ConversationSummary[]> {
  const { data, response } = await client().GET("/api/v1/conversations", {
    headers: headers(accessToken),
    params: { header: { "X-Workspace-Id": workspaceId } },
  });
  return requireData(data, response, "Conversation list");
}

export async function createConversation(
  accessToken: string,
  workspaceId: string,
  title: string,
): Promise<ConversationSnapshot> {
  const { data, response } = await client().POST("/api/v1/conversations", {
    headers: headers(accessToken),
    params: { header: { "X-Workspace-Id": workspaceId } },
    body: { title },
  });
  return requireData(data, response, "Conversation creation");
}

export async function loadConversation(
  accessToken: string,
  workspaceId: string,
  conversationId: string,
): Promise<ConversationSnapshot> {
  const { data, response } = await client().GET("/api/v1/conversations/{conversationId}", {
    headers: headers(accessToken),
    params: {
      header: { "X-Workspace-Id": workspaceId },
      path: { conversationId },
    },
  });
  return requireData(data, response, "Conversation snapshot");
}

export async function createMessage(
  accessToken: string,
  workspaceId: string,
  conversationId: string,
  content: string,
  idempotencyKey?: string,
): Promise<MessageProcessed> {
  const { data, response } = await client().POST("/api/v1/conversations/{conversationId}/messages", {
    headers: headers(accessToken),
    params: {
      header: {
        "X-Workspace-Id": workspaceId,
        ...(idempotencyKey ? { "Idempotency-Key": idempotencyKey } : {}),
      },
      path: { conversationId },
    },
    body: { content },
  });
  return requireData(data, response, "Message creation");
}

export async function cancelAgentRun(
  accessToken: string,
  workspaceId: string,
  conversationId: string,
  runId: string,
): Promise<AgentRunEvent> {
  const { data, response } = await client().POST("/api/v1/conversations/{conversationId}/runs/{runId}/cancel", {
    headers: headers(accessToken),
    params: {
      header: { "X-Workspace-Id": workspaceId },
      path: { conversationId, runId },
    },
    body: { reason: "用户主动停止分析" },
  });
  return requireData(data, response, "Agent Run cancellation");
}

/** Reads one SSE response, replaying only events after {@code afterSequence}. */
export async function subscribeToAgentRun(
  accessToken: string,
  workspaceId: string,
  conversationId: string,
  runId: string,
  afterSequence: number,
  onEvent: (event: AgentRunEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  const response = await fetch(`/api/v1/conversations/${encodeURIComponent(conversationId)}/runs/${encodeURIComponent(runId)}/events`, {
    headers: {
      ...headers(accessToken),
      Accept: "text/event-stream",
      "X-Workspace-Id": workspaceId,
      "Last-Event-ID": String(afterSequence),
    },
    signal,
  });
  if (!response.ok || response.body === null) {
    throw new Error(`Agent Run event stream failed with status ${response.status}`);
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffered = "";
  while (true) {
    const next = await reader.read();
    buffered += decoder.decode(next.value, { stream: !next.done });
    const frames = buffered.split(/\r?\n\r?\n/);
    buffered = frames.pop() ?? "";
    for (const frame of frames) {
      const event = parseAgentRunEvent(frame);
      if (event !== undefined && event.sequence > afterSequence) {
        afterSequence = event.sequence;
        onEvent(event);
      }
    }
    if (next.done) {
      return;
    }
  }
}

/** Keeps an Agent Run stream current, reconnecting from the last handled sequence when needed. */
export function watchAgentRun(
  accessToken: string,
  workspaceId: string,
  conversationId: string,
  runId: string,
  afterSequence: number,
  onEvent: (event: AgentRunEvent) => void,
): () => void {
  const controller = new AbortController();
  let lastSequence = afterSequence;
  let terminal = false;
  const receive = (event: AgentRunEvent) => {
    lastSequence = event.sequence;
    terminal ||= event.eventType === "agent.run.completed"
      || event.eventType === "agent.run.failed"
      || event.eventType === "agent.run.cancelled";
    onEvent(event);
  };

  void (async () => {
    while (!controller.signal.aborted && !terminal) {
      try {
        await subscribeToAgentRun(accessToken, workspaceId, conversationId, runId, lastSequence, receive, controller.signal);
      } catch {
        if (controller.signal.aborted) return;
        await new Promise((resolve) => setTimeout(resolve, 500));
      }
    }
  })();
  return () => controller.abort();
}

function parseAgentRunEvent(frame: string): AgentRunEvent | undefined {
  const data = frame
    .split(/\r?\n/)
    .filter((line) => line.startsWith("data:"))
    .map((line) => line.slice(5).trimStart())
    .join("\n");
  return data ? (JSON.parse(data) as AgentRunEvent) : undefined;
}

function requireData<T>(data: T | undefined, response: Response, operation: string): T {
  if (data === undefined) {
    throw new Error(`${operation} failed with status ${response.status}`);
  }
  return data;
}

export type KnowledgeSource = components["schemas"]["KnowledgeSource"];
export type KnowledgeCitation = components["schemas"]["KnowledgeCitation"];

/** 上传文本型知识来源（Markdown/纯文本/文本型 PDF）；multipart 用原生 fetch。 */
export async function uploadKnowledgeSource(
  token: string,
  workspaceId: string,
  file: File,
): Promise<KnowledgeSource> {
  const form = new FormData();
  form.append("file", file);
  form.append("title", file.name);
  const response = await fetch(`${globalThis.location?.origin ?? "http://localhost"}/api/v1/knowledge-sources`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}`, "X-Workspace-Id": workspaceId },
    body: form,
  });
  if (!response.ok) {
    throw new Error("无法上传知识来源");
  }
  return await response.json() as KnowledgeSource;
}

/** 读取当前工作区的知识来源列表。 */
export async function loadKnowledgeSources(
  token: string,
  workspaceId: string,
): Promise<KnowledgeSource[]> {
  const { data, error: fetchError, response } = await client().GET("/api/v1/knowledge-sources", {
    headers: { Authorization: `Bearer ${token}`, "X-Workspace-Id": workspaceId },
  });
  if (fetchError || response.status >= 400) {
    throw new Error("无法加载知识来源");
  }
  return data;
}

import createClient from "openapi-fetch";

import type { components, paths } from "./generated/api";

export type ConversationSummary = components["schemas"]["ConversationSummary"];
export type ConversationSnapshot = components["schemas"]["ConversationSnapshot"];
export type ConversationMessage = components["schemas"]["ConversationMessage"];
export type MessageProcessed = components["schemas"]["MessageProcessed"];

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
): Promise<MessageProcessed> {
  const { data, response } = await client().POST("/api/v1/conversations/{conversationId}/messages", {
    headers: headers(accessToken),
    params: {
      header: { "X-Workspace-Id": workspaceId },
      path: { conversationId },
    },
    body: { content },
  });
  return requireData(data, response, "Message creation");
}

function requireData<T>(data: T | undefined, response: Response, operation: string): T {
  if (data === undefined) {
    throw new Error(`${operation} failed with status ${response.status}`);
  }
  return data;
}

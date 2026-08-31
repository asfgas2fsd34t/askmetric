import createClient from "openapi-fetch";

import type { paths } from "./generated/api";

export type WorkspaceSession =
  paths["/api/v1/session"]["get"]["responses"][200]["content"]["application/json"];

export async function loadWorkspaceSession(
  accessToken: string,
  requestedWorkspaceId?: string,
): Promise<WorkspaceSession> {
  const client = createClient<paths>({ baseUrl: globalThis.location?.origin ?? "http://localhost" });
  const { data, response } = await client.GET("/api/v1/session", {
    headers: { Authorization: `Bearer ${accessToken}` },
    params: { header: { "X-Workspace-Id": requestedWorkspaceId } },
  });
  if (!data) {
    throw new Error(`Workspace session request failed with status ${response.status}`);
  }
  return data;
}

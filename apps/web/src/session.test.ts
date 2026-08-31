import { describe, expect, it, vi } from "vitest";

import { loadWorkspaceSession } from "./session";

describe("Workspace Membership", () => {
  it("uses the Workspace Membership confirmed by Java after selection", async () => {
    const response = {
      user: {
        id: "user-1",
        username: "alice",
        displayName: "Alice Chen",
        email: "alice@example.com",
      },
      currentMembership: {
        membershipId: "membership-growth",
        workspaceId: "workspace-growth",
        workspaceName: "Growth Workspace",
      },
      memberships: [],
    };
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify(response), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      }),
    );
    vi.stubGlobal("fetch", fetchMock);

    const session = await loadWorkspaceSession("access-token", "workspace-growth");

    const request = fetchMock.mock.calls[0][0] as Request;
    expect(request.url.endsWith("/api/v1/session")).toBe(true);
    expect(request.headers.get("Authorization")).toBe("Bearer access-token");
    expect(request.headers.get("X-Workspace-Id")).toBe("workspace-growth");
    expect(session.currentMembership.workspaceId).toBe("workspace-growth");
    vi.unstubAllGlobals();
  });
});

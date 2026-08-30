package dev.askmetric.server.workspace;

import java.util.List;

record WorkspaceSession(
        CurrentUser user,
        WorkspaceMembership currentMembership,
        List<WorkspaceMembership> memberships) {

    WorkspaceSession {
        memberships = List.copyOf(memberships);
    }

    record CurrentUser(String id, String username, String displayName, String email) {
    }

    record WorkspaceMembership(String membershipId, String workspaceId, String workspaceName) {
    }
}

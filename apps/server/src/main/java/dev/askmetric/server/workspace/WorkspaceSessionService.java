package dev.askmetric.server.workspace;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

@Service
class WorkspaceSessionService {
    static final String DEMO_USER_SUBJECT = "00000000-0000-0000-0000-000000000001";

    // ponytail: T04 keeps deterministic demo memberships in memory; T05 replaces this with persisted lookup and RLS.
    private final Map<String, List<WorkspaceSession.WorkspaceMembership>> membershipsByUser = Map.of(
            DEMO_USER_SUBJECT,
            List.of(
                    new WorkspaceSession.WorkspaceMembership(
                            "membership-demo", "workspace-demo", "Demo Workspace"),
                    new WorkspaceSession.WorkspaceMembership(
                            "membership-growth", "workspace-growth", "Growth Workspace")));

    WorkspaceSession current(Jwt identity, Optional<String> requestedWorkspaceId) {
        List<WorkspaceSession.WorkspaceMembership> memberships = membershipsByUser
                .getOrDefault(identity.getSubject(), List.of());
        if (memberships.isEmpty()) {
            throw new AccessDeniedException("Authenticated user has no Workspace Membership");
        }

        WorkspaceSession.WorkspaceMembership currentMembership = requestedWorkspaceId
                .filter(workspaceId -> !workspaceId.isBlank())
                .map(workspaceId -> memberships.stream()
                        .filter(membership -> membership.workspaceId().equals(workspaceId))
                        .findFirst()
                        .orElseThrow(() -> new AccessDeniedException("Workspace Membership not found")))
                .orElse(memberships.getFirst());
        String username = claim(identity, "preferred_username", identity.getSubject());
        return new WorkspaceSession(
                new WorkspaceSession.CurrentUser(
                        identity.getSubject(),
                        username,
                        claim(identity, "name", username),
                        claim(identity, "email", "")),
                currentMembership,
                memberships);
    }

    private static String claim(Jwt identity, String name, String fallback) {
        String value = identity.getClaimAsString(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}

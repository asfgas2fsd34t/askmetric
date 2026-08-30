package dev.askmetric.server.workspace;

import java.util.Optional;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

@Service
class WorkspaceSessionService {
    private final WorkspaceAuthorizationService authorization;

    WorkspaceSessionService(WorkspaceAuthorizationService authorization) {
        this.authorization = authorization;
    }

    WorkspaceSession current(Jwt identity, Optional<String> requestedWorkspaceId) {
        WorkspaceAccess access = authorization.authorize(
                identity, requestedWorkspaceId, WorkspacePermission.VIEW_WORKSPACE);
        String username = claim(identity, "preferred_username", identity.getSubject());
        return new WorkspaceSession(
                new WorkspaceSession.CurrentUser(
                        identity.getSubject(),
                        username,
                        claim(identity, "name", username),
                        claim(identity, "email", "")),
                access.getCurrentMembership(),
                access.getMemberships());
    }

    private static String claim(Jwt identity, String name, String fallback) {
        String value = identity.getClaimAsString(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}

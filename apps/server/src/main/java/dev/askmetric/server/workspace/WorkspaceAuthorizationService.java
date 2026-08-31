package dev.askmetric.server.workspace;

import java.util.List;
import java.util.Optional;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkspaceAuthorizationService {
    private final WorkspaceAccessRepository repository;

    WorkspaceAuthorizationService(WorkspaceAccessRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public WorkspaceAccess authorize(
            Jwt identity,
            Optional<String> requestedWorkspaceId,
            WorkspacePermission requiredPermission) {
        String userSubject = identity.getSubject();
        List<WorkspaceAccess.Membership> memberships = repository.memberships(userSubject);
        if (memberships.isEmpty()) {
            throw new AccessDeniedException("Authenticated user has no Workspace Membership");
        }

        WorkspaceAccess.Membership currentMembership = requestedWorkspaceId
                .filter(workspaceId -> !workspaceId.isBlank())
                .map(workspaceId -> memberships.stream()
                        .filter(membership -> membership.getWorkspaceId().equals(workspaceId))
                        .findFirst()
                        .orElseThrow(() -> new AccessDeniedException("Workspace Membership not found")))
                .orElse(memberships.getFirst());
        if (!currentMembership.getPermissions().contains(requiredPermission)) {
            throw new AccessDeniedException("Workspace Membership does not grant " + requiredPermission);
        }
        WorkspaceAccess.Policy policy = repository.currentPolicy(userSubject, currentMembership.getWorkspaceId())
                .orElseThrow(() -> new AccessDeniedException("Workspace Policy not found"));
        if (!policy.getAllowedPermissions().contains(requiredPermission)) {
            throw new AccessDeniedException("Workspace Policy does not allow " + requiredPermission);
        }
        return new WorkspaceAccess(currentMembership, memberships, policy);
    }

    @Transactional(readOnly = true)
    public WorkspaceAccess authorizeConversation(
            Jwt identity,
            Optional<String> requestedWorkspaceId,
            String conversationId,
            WorkspacePermission requiredPermission) {
        WorkspaceAccess access = authorize(identity, requestedWorkspaceId, requiredPermission);
        if (!repository.conversationExists(
                identity.getSubject(), access.currentWorkspaceId(), conversationId)) {
            throw new AccessDeniedException("Conversation not found in Workspace");
        }
        return access;
    }
}

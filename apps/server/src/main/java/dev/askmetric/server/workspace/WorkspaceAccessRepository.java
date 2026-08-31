package dev.askmetric.server.workspace;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Repository;

@Repository
class WorkspaceAccessRepository {
    private final WorkspaceAccessMapper mapper;

    WorkspaceAccessRepository(WorkspaceAccessMapper mapper) {
        this.mapper = mapper;
    }

    List<WorkspaceAccess.Membership> memberships(String userSubject) {
        return mapper.memberships(userSubject).stream()
                .map(row -> new WorkspaceAccess.Membership(
                        row.getMembershipId(),
                        row.getWorkspaceId(),
                        row.getWorkspaceName(),
                        permissions(row.getPermissions())))
                .toList();
    }

    Optional<WorkspaceAccess.Policy> currentPolicy(String userSubject, String workspaceId) {
        return mapper.currentPolicy(userSubject, workspaceId).map(row -> new WorkspaceAccess.Policy(
                row.getVersion(),
                row.isRequiresSeparateApprover(),
                permissions(row.getAllowedPermissions())));
    }

    boolean conversationExists(String userSubject, String workspaceId, String conversationId) {
        return mapper.conversationExists(userSubject, workspaceId, conversationId);
    }

    private static Set<WorkspacePermission> permissions(String[] permissions) {
        return Arrays.stream(permissions)
                .map(WorkspacePermission::valueOf)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}

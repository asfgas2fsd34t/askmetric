package dev.askmetric.server.workspace;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;
import java.util.Set;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class WorkspaceAccess {
    private Membership currentMembership;
    private List<Membership> memberships;
    private Policy policy;

    public WorkspaceAccess(Membership currentMembership, List<Membership> memberships, Policy policy) {
        this.currentMembership = currentMembership;
        this.memberships = List.copyOf(memberships);
        this.policy = policy;
    }

    public void setMemberships(List<Membership> memberships) {
        this.memberships = List.copyOf(memberships);
    }

    public String currentWorkspaceId() {
        return currentMembership.getWorkspaceId();
    }

    @Data
    @NoArgsConstructor
    public static class Membership {
        private String membershipId;
        private String workspaceId;
        private String workspaceName;
        @JsonIgnore
        private Set<WorkspacePermission> permissions;

        public Membership(
                String membershipId,
                String workspaceId,
                String workspaceName,
                Set<WorkspacePermission> permissions) {
            this.membershipId = membershipId;
            this.workspaceId = workspaceId;
            this.workspaceName = workspaceName;
            this.permissions = Set.copyOf(permissions);
        }

        public void setPermissions(Set<WorkspacePermission> permissions) {
            this.permissions = Set.copyOf(permissions);
        }
    }

    @Data
    @NoArgsConstructor
    public static class Policy {
        private int version;
        private boolean requiresSeparateApprover;
        private Set<WorkspacePermission> allowedPermissions;

        public Policy(int version, boolean requiresSeparateApprover, Set<WorkspacePermission> allowedPermissions) {
            this.version = version;
            this.requiresSeparateApprover = requiresSeparateApprover;
            this.allowedPermissions = Set.copyOf(allowedPermissions);
        }

        public void setAllowedPermissions(Set<WorkspacePermission> allowedPermissions) {
            this.allowedPermissions = Set.copyOf(allowedPermissions);
        }
    }
}

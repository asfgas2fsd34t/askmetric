package dev.askmetric.server.workspace;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
class WorkspaceSession {
    private CurrentUser user;
    private WorkspaceAccess.Membership currentMembership;
    private List<WorkspaceAccess.Membership> memberships;

    WorkspaceSession(
            CurrentUser user,
            WorkspaceAccess.Membership currentMembership,
            List<WorkspaceAccess.Membership> memberships) {
        this.user = user;
        this.currentMembership = currentMembership;
        this.memberships = List.copyOf(memberships);
    }

    public void setMemberships(List<WorkspaceAccess.Membership> memberships) {
        this.memberships = List.copyOf(memberships);
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class CurrentUser {
        private String id;
        private String username;
        private String displayName;
        private String email;
    }
}

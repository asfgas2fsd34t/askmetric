package dev.askmetric.server.workspace;

import java.util.List;
import java.util.Optional;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.type.ArrayTypeHandler;

@Mapper
interface WorkspaceAccessMapper {
    @Results({
            @Result(column = "membership_id", property = "membershipId"),
            @Result(column = "workspace_id", property = "workspaceId"),
            @Result(column = "workspace_name", property = "workspaceName"),
            @Result(column = "permissions", property = "permissions", typeHandler = ArrayTypeHandler.class)
    })
    @Select("""
            select membership.membership_id, workspace.workspace_id, workspace.workspace_name,
                   membership.permissions
            from workspace_membership membership
            join workspace on workspace.workspace_id = membership.workspace_id
            where membership.user_subject = #{userSubject}
            order by membership.membership_id
            """)
    List<MembershipRow> memberships(String userSubject);

    @Results({
            @Result(column = "policy_version", property = "version"),
            @Result(column = "requires_separate_approver", property = "requiresSeparateApprover"),
            @Result(
                    column = "allowed_permissions",
                    property = "allowedPermissions",
                    typeHandler = ArrayTypeHandler.class)
    })
    @Select("""
            select policy_version, requires_separate_approver, allowed_permissions
            from workspace_policy policy
            where policy.workspace_id = #{workspaceId}
              and exists (
                  select 1
                  from workspace_membership membership
                  where membership.workspace_id = policy.workspace_id
                    and membership.user_subject = #{userSubject}
              )
            """)
    Optional<PolicyRow> currentPolicy(String userSubject, String workspaceId);

    @Select("""
            select exists (
                select 1
                from conversation
                join workspace_membership membership
                  on membership.workspace_id = conversation.workspace_id
                where conversation.conversation_id = #{conversationId}
                  and conversation.workspace_id = #{workspaceId}
                  and membership.user_subject = #{userSubject}
            )
            """)
    boolean conversationExists(String userSubject, String workspaceId, String conversationId);

    @Data
    @NoArgsConstructor
    class MembershipRow {
        private String membershipId;
        private String workspaceId;
        private String workspaceName;
        private String[] permissions;
    }

    @Data
    @NoArgsConstructor
    class PolicyRow {
        private int version;
        private boolean requiresSeparateApprover;
        private String[] allowedPermissions;
    }
}

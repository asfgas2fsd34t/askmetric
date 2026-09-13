package dev.askmetric.server.approval;

import dev.askmetric.server.workspace.WorkspaceAccess;
import dev.askmetric.server.workspace.WorkspaceAuthorizationService;
import dev.askmetric.server.workspace.WorkspacePermission;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 操作提案的人工边界：确认创建与放弃都是发起者动作；
 * 审批、拒绝与执行属于 T23/T24，提案在本模块不存在任何 Agent 可达的执行路径。
 */
@RestController
public class ActionProposalController {
    private final ActionProposalService service;
    private final WorkspaceAuthorizationService workspaceAuthorization;

    public ActionProposalController(
            ActionProposalService service,
            WorkspaceAuthorizationService workspaceAuthorization) {
        this.service = service;
        this.workspaceAuthorization = workspaceAuthorization;
    }

    /** 发起者显式确认提案创建；确认后提案等待审批且参数不可变。 */
    @PostMapping("/api/v1/action-proposals/{actionProposalId}/confirmation")
    public ActionProposal confirm(
            @AuthenticationPrincipal Jwt identity,
            @RequestHeader(value = "X-Workspace-Id", required = false) String requestedWorkspaceId,
            @PathVariable String actionProposalId) {
        WorkspaceAccess access = workspaceAuthorization.authorize(
                identity, Optional.ofNullable(requestedWorkspaceId), WorkspacePermission.CREATE_MESSAGE);
        return service.confirm(identity.getSubject(), access.currentWorkspaceId(), actionProposalId);
    }

    /** 发起者放弃待确认提案；放弃的提案不会产生任何副作用。 */
    @PostMapping("/api/v1/action-proposals/{actionProposalId}/discard")
    public ActionProposal discard(
            @AuthenticationPrincipal Jwt identity,
            @RequestHeader(value = "X-Workspace-Id", required = false) String requestedWorkspaceId,
            @PathVariable String actionProposalId) {
        WorkspaceAccess access = workspaceAuthorization.authorize(
                identity, Optional.ofNullable(requestedWorkspaceId), WorkspacePermission.CREATE_MESSAGE);
        return service.discard(identity.getSubject(), access.currentWorkspaceId(), actionProposalId);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse(exception.getMessage()));
    }

    public record ErrorResponse(String error) {
    }
}

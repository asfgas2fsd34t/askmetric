package dev.askmetric.server.query;

import dev.askmetric.server.workspace.WorkspaceAuthorizationService;
import dev.askmetric.server.workspace.WorkspacePermission;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Query Gateway 的 Java HTTP 边界。 */
@RestController
@RequestMapping("/api/v1/queries")
public class QueryController {
    private final QueryGateway gateway;
    private final WorkspaceAuthorizationService authorization;

    public QueryController(QueryGateway gateway, WorkspaceAuthorizationService authorization) {
        this.gateway = gateway;
        this.authorization = authorization;
    }

    /** 校验并执行当前工作区成员提交的只读查询。 */
    @PostMapping
    public ResponseEntity<QueryResult> execute(
            @AuthenticationPrincipal Jwt identity,
            @RequestHeader(value = "X-Workspace-Id", required = false) String requestedWorkspaceId,
            @RequestBody QueryRequest request) {
        String workspaceId = authorization.authorize(
                        identity,
                        Optional.ofNullable(requestedWorkspaceId),
                        WorkspacePermission.VIEW_AGENT_RUN)
                .currentWorkspaceId();
        request.setWorkspaceId(workspaceId);
        return ResponseEntity.ok(gateway.execute(identity.getSubject(), request));
    }
}

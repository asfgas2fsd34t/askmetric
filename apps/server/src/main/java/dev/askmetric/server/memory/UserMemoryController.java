package dev.askmetric.server.memory;

import dev.askmetric.server.agent.AgentQueryGrantService;
import dev.askmetric.server.agent.AgentRunContext;
import dev.askmetric.server.agent.AgentRunMapper;
import dev.askmetric.server.workspace.WorkspaceAccess;
import dev.askmetric.server.workspace.WorkspaceAuthorizationService;
import dev.askmetric.server.workspace.WorkspacePermission;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * User Memory 的 HTTP 边界：创建需确认、查看与删除都是所有者动作；
 * Python Agent 以运行绑定的查询授权读取已确认记忆，且只能看到
 * 运行发起人自己在该工作区确认过的条目。
 */
@RestController
public class UserMemoryController {
    private final UserMemoryService service;
    private final WorkspaceAuthorizationService workspaceAuthorization;
    private final AgentRunMapper agentRunMapper;
    private final AgentQueryGrantService grantService;

    public UserMemoryController(
            UserMemoryService service,
            WorkspaceAuthorizationService workspaceAuthorization,
            AgentRunMapper agentRunMapper,
            AgentQueryGrantService grantService) {
        this.service = service;
        this.workspaceAuthorization = workspaceAuthorization;
        this.agentRunMapper = agentRunMapper;
        this.grantService = grantService;
    }

    /** 登记一条待确认记忆。 */
    @PostMapping("/api/v1/user-memories")
    public ResponseEntity<UserMemory> propose(
            @AuthenticationPrincipal Jwt identity,
            @RequestHeader(value = "X-Workspace-Id", required = false) String requestedWorkspaceId,
            @RequestBody(required = false) CreateMemoryRequest request) {
        WorkspaceAccess access = workspaceAuthorization.authorize(
                identity, Optional.ofNullable(requestedWorkspaceId), WorkspacePermission.CREATE_MESSAGE);
        UserMemory memory = service.propose(
                identity.getSubject(),
                access.currentWorkspaceId(),
                request == null ? null : request.getContent(),
                null,
                null);
        return ResponseEntity.status(HttpStatus.CREATED).body(memory);
    }

    /** 所有者显式确认记忆；确认后才对 Agent 可见。 */
    @PostMapping("/api/v1/user-memories/{userMemoryId}/confirmation")
    public UserMemory confirm(
            @AuthenticationPrincipal Jwt identity,
            @RequestHeader(value = "X-Workspace-Id", required = false) String requestedWorkspaceId,
            @PathVariable String userMemoryId) {
        WorkspaceAccess access = workspaceAuthorization.authorize(
                identity, Optional.ofNullable(requestedWorkspaceId), WorkspacePermission.CREATE_MESSAGE);
        return service.confirm(identity.getSubject(), access.currentWorkspaceId(), userMemoryId);
    }

    /** 所有者查看自己的全部记忆（含待确认）。 */
    @GetMapping("/api/v1/user-memories")
    public List<UserMemory> list(
            @AuthenticationPrincipal Jwt identity,
            @RequestHeader(value = "X-Workspace-Id", required = false) String requestedWorkspaceId) {
        WorkspaceAccess access = workspaceAuthorization.authorize(
                identity, Optional.ofNullable(requestedWorkspaceId), WorkspacePermission.VIEW_AGENT_RUN);
        return service.list(identity.getSubject(), access.currentWorkspaceId());
    }

    /** 所有者删除记忆；删除后不可再被使用。 */
    @DeleteMapping("/api/v1/user-memories/{userMemoryId}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal Jwt identity,
            @RequestHeader(value = "X-Workspace-Id", required = false) String requestedWorkspaceId,
            @PathVariable String userMemoryId) {
        WorkspaceAccess access = workspaceAuthorization.authorize(
                identity, Optional.ofNullable(requestedWorkspaceId), WorkspacePermission.CREATE_MESSAGE);
        service.delete(identity.getSubject(), access.currentWorkspaceId(), userMemoryId);
        return ResponseEntity.noContent().build();
    }

    /** Agent 读取入口：查询授权决定身份；只返回已确认且属于本工作区的记忆。 */
    @GetMapping("/api/v1/agent-run-memories")
    public List<UserMemory> listForRun(
            @RequestHeader(value = "X-Query-Grant", required = false) String grant,
            @RequestParam("runId") String runId) {
        if (runId == null || runId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "runId is required");
        }
        if (!grantService.validate(runId, grant, Instant.now())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Query grant is invalid or expired");
        }
        AgentRunContext context = agentRunMapper.findRunContext(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent Run not found"));
        return service.listConfirmedForAgent(context.getAuthorSubject(), context.getWorkspaceId());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse(exception.getMessage()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleStatusException(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode())
                .body(new ErrorResponse(exception.getReason()));
    }

    /** 登记记忆的请求体。 */
    public static class CreateMemoryRequest {
        private String content;

        public String getContent() {
            return content;
        }
    }

    /** 记忆边界返回给调用方的错误说明。 */
    public record ErrorResponse(String error) {
    }
}

package dev.askmetric.server.agent;

import dev.askmetric.server.workspace.WorkspaceAuthorizationService;
import dev.askmetric.server.workspace.WorkspacePermission;
import java.util.Optional;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Agent Run 的 HTTP 边界：创建运行，并以可断线续传的 SSE 将执行事件交给对话客户端。
 */
@RestController
@RequestMapping("/api/v1/conversations")
public class AgentRunController {
    private final AgentRunService service;
    private final WorkspaceAuthorizationService workspaceAuthorization;

    public AgentRunController(AgentRunService service, WorkspaceAuthorizationService workspaceAuthorization) {
        this.service = service;
        this.workspaceAuthorization = workspaceAuthorization;
    }

    @GetMapping(value = "/{conversationId}/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
            @AuthenticationPrincipal Jwt identity,
            @RequestHeader(value = "X-Workspace-Id", required = false) String requestedWorkspaceId,
            @PathVariable String conversationId,
            @PathVariable String runId,
            @RequestHeader("Last-Event-ID") Optional<String> lastEventId) {
        if (runId.isBlank() || conversationId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "conversationId and runId are required");
        }
        String workspaceId = workspaceAuthorization
                .authorizeConversation(
                        identity,
                        Optional.ofNullable(requestedWorkspaceId),
                        conversationId,
                        WorkspacePermission.VIEW_AGENT_RUN)
                .currentWorkspaceId();
        if (!service.exists(workspaceId, conversationId, runId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent Run not found");
        }
        long afterSequence;
        try {
            afterSequence = lastEventId.map(Long::parseLong).orElse(0L);
        } catch (NumberFormatException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Last-Event-ID must be a sequence number");
        }
        if (afterSequence < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Last-Event-ID must not be negative");
        }
        // SSE 标准 Last-Event-ID 保存的是已收到的 sequence；仅补放其后的事件。
        SseEmitter emitter = new SseEmitter(120_000L);
        service.addReplay(conversationId, runId, afterSequence, emitter);
        return emitter;
    }

    @PostMapping("/{conversationId}/runs/{runId}/cancel")
    public AgentRunEvent cancel(
            @AuthenticationPrincipal Jwt identity,
            @RequestHeader(value = "X-Workspace-Id", required = false) String requestedWorkspaceId,
            @PathVariable String conversationId,
            @PathVariable String runId,
            @RequestBody(required = false) CancelAgentRunRequest request) {
        if (runId.isBlank() || conversationId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "conversationId and runId are required");
        }
        String reason = request == null || request.getReason() == null || request.getReason().isBlank()
                ? "用户主动停止分析"
                : request.getReason().strip();
        if (reason.length() > 500 || reason.chars().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reason must be at most 500 visible characters");
        }
        String workspaceId = workspaceAuthorization
                .authorizeConversation(
                        identity,
                        Optional.ofNullable(requestedWorkspaceId),
                        conversationId,
                        WorkspacePermission.CREATE_MESSAGE)
                .currentWorkspaceId();
        if (!service.exists(workspaceId, conversationId, runId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent Run not found");
        }
        return service.cancel(identity.getSubject(), workspaceId, conversationId, runId, reason)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT, "Agent Run is not an active analysis run"));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleStatusException(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode())
                .body(new ErrorResponse(exception.getReason()));
    }

    /**
     * HTTP 请求校验或运行状态错误的统一响应。
     *
     * @param error 面向客户端的错误说明
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ErrorResponse {
        private String error;
    }
}

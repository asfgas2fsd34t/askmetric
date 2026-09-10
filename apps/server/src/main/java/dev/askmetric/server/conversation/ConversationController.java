package dev.askmetric.server.conversation;

import dev.askmetric.server.agent.AgentRunController.ErrorResponse;
import dev.askmetric.server.workspace.WorkspaceAccess;
import dev.askmetric.server.workspace.WorkspaceAuthorizationService;
import dev.askmetric.server.workspace.WorkspacePermission;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {
    private final ConversationService service;
    private final WorkspaceAuthorizationService workspaceAuthorization;

    public ConversationController(
            ConversationService service,
            WorkspaceAuthorizationService workspaceAuthorization) {
        this.service = service;
        this.workspaceAuthorization = workspaceAuthorization;
    }

    @GetMapping
    public List<ConversationListItem> list(
            @AuthenticationPrincipal Jwt identity,
            @RequestHeader(value = "X-Workspace-Id", required = false) String requestedWorkspaceId) {
        WorkspaceAccess access = workspaceAuthorization.authorize(
                identity,
                Optional.ofNullable(requestedWorkspaceId),
                WorkspacePermission.VIEW_CONVERSATION);
        return service.list(identity.getSubject(), access.currentWorkspaceId());
    }

    @PostMapping
    public ResponseEntity<?> create(
            @AuthenticationPrincipal Jwt identity,
            @RequestHeader(value = "X-Workspace-Id", required = false) String requestedWorkspaceId,
            @RequestBody CreateConversationRequest request) {
        try {
            WorkspaceAccess access = workspaceAuthorization.authorize(
                    identity,
                    Optional.ofNullable(requestedWorkspaceId),
                    WorkspacePermission.CREATE_CONVERSATION);
            ConversationSnapshot snapshot = service.create(
                    identity.getSubject(), access.currentWorkspaceId(), request);
            return ResponseEntity.created(URI.create("/api/v1/conversations/" + snapshot.getConversationId()))
                    .body(snapshot);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(new ErrorResponse(exception.getMessage()));
        }
    }

    @GetMapping("/{conversationId}")
    public ConversationSnapshot snapshot(
            @AuthenticationPrincipal Jwt identity,
            @RequestHeader(value = "X-Workspace-Id", required = false) String requestedWorkspaceId,
            @PathVariable String conversationId) {
        WorkspaceAccess access = workspaceAuthorization.authorizeConversation(
                identity,
                Optional.ofNullable(requestedWorkspaceId),
                conversationId,
                WorkspacePermission.VIEW_CONVERSATION);
        return service.snapshot(identity.getSubject(), access.currentWorkspaceId(), conversationId);
    }

    @PostMapping("/{conversationId}/messages")
    public ResponseEntity<?> appendMessage(
            @AuthenticationPrincipal Jwt identity,
            @RequestHeader(value = "X-Workspace-Id", required = false) String requestedWorkspaceId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @PathVariable String conversationId,
            @RequestBody CreateMessageRequest request) {
        try {
            WorkspaceAccess access = workspaceAuthorization.authorizeConversation(
                    identity,
                    Optional.ofNullable(requestedWorkspaceId),
                    conversationId,
                    WorkspacePermission.CREATE_MESSAGE);
            MessageProcessed message = service.submitMessage(
                    identity.getSubject(), access.currentWorkspaceId(), conversationId, request, idempotencyKey);
            return ResponseEntity.created(URI.create(
                            "/api/v1/conversations/%s/messages/%s"
                                    .formatted(conversationId, message.getUserMessage().getMessageId())))
                    .body(message);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(new ErrorResponse(exception.getMessage()));
        } catch (IdempotencyConflictException exception) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(exception.getMessage()));
        }
    }

    /** 为一段明确的消息范围创建版本化对话摘要；原始 Message 保持不变。 */
    @PostMapping("/{conversationId}/summaries")
    public ResponseEntity<?> createSummary(
            @AuthenticationPrincipal Jwt identity,
            @RequestHeader(value = "X-Workspace-Id", required = false) String requestedWorkspaceId,
            @PathVariable String conversationId,
            @RequestBody CreateSummaryRequest request) {
        try {
            WorkspaceAccess access = workspaceAuthorization.authorizeConversation(
                    identity,
                    Optional.ofNullable(requestedWorkspaceId),
                    conversationId,
                    WorkspacePermission.VIEW_CONVERSATION);
            ConversationSummary summary = service.createSummary(
                    identity.getSubject(), access.currentWorkspaceId(), conversationId, request);
            return ResponseEntity.created(URI.create(
                            "/api/v1/conversations/%s/summaries/%s"
                                    .formatted(conversationId, summary.getConversationSummaryId())))
                    .body(summary);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(new ErrorResponse(exception.getMessage()));
        }
    }

    /** 创建对话摘要的请求体：覆盖的消息序号范围（含两端）。 */
    public static class CreateSummaryRequest {
        private Long fromSequence;
        private Long toSequence;

        public Long getFromSequence() {
            return fromSequence;
        }

        public Long getToSequence() {
            return toSequence;
        }
    }
}

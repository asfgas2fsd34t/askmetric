package dev.askmetric.server.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.askmetric.server.workspace.WorkspaceAccess;
import dev.askmetric.server.workspace.WorkspaceAuthorizationService;
import dev.askmetric.server.workspace.WorkspacePermission;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class AgentRunControllerTest {
    @Test
    void replaysOnlyEventsAfterTheClientLastEventId() {
        AgentRunService service = mock(AgentRunService.class);
        WorkspaceAuthorizationService authorization = mock(WorkspaceAuthorizationService.class);
        AgentRunController controller = new AgentRunController(service, authorization);
        Jwt identity = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("user-1")
                .build();
        WorkspaceAccess access = workspaceAccess();
        when(authorization.authorizeConversation(
                        identity,
                        Optional.of("workspace-demo"),
                        "conversation-1",
                        WorkspacePermission.VIEW_AGENT_RUN))
                .thenReturn(access);
        when(service.exists("workspace-demo", "conversation-1", "run-1")).thenReturn(true);

        SseEmitter emitter = controller.events(
                identity, "workspace-demo", "conversation-1", "run-1", Optional.of("2"));

        assertThat(emitter).isNotNull();
        verify(service).addReplay(
                eq("workspace-demo"), eq("conversation-1"), eq("run-1"), eq(2L), eq(emitter));
    }

    private static WorkspaceAccess workspaceAccess() {
        WorkspaceAccess.Membership membership = new WorkspaceAccess.Membership(
                "membership-1", "workspace-demo", "Demo", Set.of(WorkspacePermission.VIEW_AGENT_RUN));
        return new WorkspaceAccess(
                membership,
                List.of(membership),
                new WorkspaceAccess.Policy(1, false, Set.of(WorkspacePermission.VIEW_AGENT_RUN)));
    }
}

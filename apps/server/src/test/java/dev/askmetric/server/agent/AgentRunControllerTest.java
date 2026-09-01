package dev.askmetric.server.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.askmetric.server.workspace.WorkspaceAccess;
import dev.askmetric.server.workspace.WorkspaceAuthorizationService;
import dev.askmetric.server.workspace.WorkspacePermission;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class AgentRunControllerTest {
    @Test
    void includesRunLocationWhenPublishingFails() {
        var store = new AgentRunStore();
        var published = new AtomicReference<AgentRunRequest>();
        var gateway = new RocketMqGateway() {
            @Override
            public void publish(AgentRunRequest request) {
                published.set(request);
                throw new IllegalStateException("broker unavailable");
            }

            @Override
            public void close() {
            }
        };
        var workspaceAuthorization = mock(WorkspaceAuthorizationService.class);
        var membership = new WorkspaceAccess.Membership(
                "membership-demo", "workspace-demo", "Demo Workspace", Set.of(WorkspacePermission.CREATE_MESSAGE));
        when(workspaceAuthorization.authorizeConversation(any(), any(), any(), any()))
                .thenReturn(new WorkspaceAccess(
                        membership,
                        List.of(membership),
                        new WorkspaceAccess.Policy(1, false, Set.of(WorkspacePermission.CREATE_MESSAGE))));
        var controller = new AgentRunController(new AgentRunService(store, gateway), workspaceAuthorization);
        var identity = Jwt.withTokenValue("token").header("alg", "none").subject("user-1").build();

        var response = controller.submit(
                identity, "workspace-demo", "conversation-1", new AgentRunSubmission("hello"));

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).isInstanceOf(AgentRunAccepted.class);
        var accepted = (AgentRunAccepted) response.getBody();
        assertThat(accepted.getRunId()).isEqualTo(published.get().getRunId());
        assertThat(accepted.getEventsUrl()).contains(accepted.getRunId());
    }
}

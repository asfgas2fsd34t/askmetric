package dev.askmetric.server.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class WorkspaceIsolationIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName
            .parse("public.ecr.aws/docker/library/postgres:16.10-alpine")
            .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("askmetric")
            .withUsername("askmetric_owner")
            .withPassword("askmetric_owner")
            .withInitScript("db/test-init.sql");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "askmetric_app");
        registry.add("spring.datasource.password", () -> "askmetric_app");
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    @Autowired
    WorkspaceAccessRepository repository;

    @Autowired
    WorkspaceSessionService sessions;

    @Autowired
    WorkspaceAuthorizationService authorization;

    @Autowired
    MockMvc mvc;

    @Test
    void workspaceScopedQueriesHideOtherWorkspaceMembershipsWorkspacePoliciesAndConversations() {
        String alice = "00000000-0000-0000-0000-000000000001";
        List<String> visibleMemberships = repository.memberships(alice).stream()
                .map(WorkspaceAccess.Membership::getWorkspaceId)
                .toList();

        assertThat(visibleMemberships).containsExactly("workspace-demo", "workspace-growth");
        assertThat(repository.currentPolicy(alice, "workspace-demo")).isPresent();
        assertThat(repository.currentPolicy(alice, "workspace-finance")).isEmpty();
        assertThat(repository.conversationExists(alice, "workspace-demo", "conversation-demo")).isTrue();
        assertThat(repository.conversationExists(alice, "workspace-demo", "conversation-finance")).isFalse();
    }

    @Test
    void workspaceScopedQueryRejectsWorkspacePolicyWithoutWorkspaceMembership() {
        assertThat(repository.currentPolicy(
                "00000000-0000-0000-0000-000000000001", "workspace-finance"))
                .isEmpty();
    }

    @Test
    void persistedWorkspaceMembershipAuthorizesSelectionAndRejectsAnotherWorkspace() {
        Jwt alice = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("00000000-0000-0000-0000-000000000001")
                .claim("preferred_username", "alice")
                .build();

        WorkspaceSession session = sessions.current(alice, Optional.of("workspace-growth"));

        assertThat(session.getCurrentMembership().getWorkspaceId()).isEqualTo("workspace-growth");
        assertThat(session.getMemberships())
                .extracting(WorkspaceAccess.Membership::getWorkspaceId)
                .containsExactly("workspace-demo", "workspace-growth");
        assertThatThrownBy(() -> sessions.current(alice, Optional.of("workspace-finance")))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Workspace Membership not found");
    }

    @Test
    void workspaceMembershipAndWorkspacePolicyAuthorizeAgentRunCreation() {
        Jwt alice = identity("00000000-0000-0000-0000-000000000001");
        Jwt bob = identity("00000000-0000-0000-0000-000000000002");

        WorkspaceAccess access = authorization.authorize(
                alice, Optional.of("workspace-demo"), WorkspacePermission.CREATE_AGENT_RUN);

        assertThat(access.currentWorkspaceId()).isEqualTo("workspace-demo");
        assertThatThrownBy(() -> authorization.authorize(
                        alice, Optional.of("workspace-growth"), WorkspacePermission.CREATE_AGENT_RUN))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Workspace Membership does not grant CREATE_AGENT_RUN");
        assertThatThrownBy(() -> authorization.authorize(
                        bob, Optional.of("workspace-finance"), WorkspacePermission.CREATE_AGENT_RUN))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Workspace Policy does not allow CREATE_AGENT_RUN");
    }

    @Test
    void businessUserCannotCreateAgentRunForConversationInAnotherWorkspace() throws Exception {
        mvc.perform(post("/api/v1/conversations/conversation-finance/runs")
                        .header("X-Workspace-Id", "workspace-demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"forged Conversation\"}")
                        .with(jwt().jwt(token -> token
                                .subject("00000000-0000-0000-0000-000000000001"))))
                .andExpect(status().isForbidden());
    }

    private static Jwt identity(String subject) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(subject)
                .build();
    }
}

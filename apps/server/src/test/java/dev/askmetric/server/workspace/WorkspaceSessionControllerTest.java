package dev.askmetric.server.workspace;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(WorkspaceSessionController.class)
@Import({SecurityConfiguration.class, WorkspaceSessionService.class})
class WorkspaceSessionControllerTest {
    static final String DEMO_USER_SUBJECT = "00000000-0000-0000-0000-000000000001";

    @Autowired
    private MockMvc mvc;

    @Test
    void unauthenticatedRequestCannotEnterWorkspace() throws Exception {
        mvc.perform(get("/api/v1/session"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedBusinessUserSeesCurrentWorkspaceMembership() throws Exception {
        mvc.perform(get("/api/v1/session").with(jwt().jwt(token -> token
                        .subject(DEMO_USER_SUBJECT)
                        .claim("preferred_username", "alice")
                        .claim("name", "Alice Chen")
                        .claim("email", "alice@example.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(DEMO_USER_SUBJECT))
                .andExpect(jsonPath("$.user.username").value("alice"))
                .andExpect(jsonPath("$.user.displayName").value("Alice Chen"))
                .andExpect(jsonPath("$.user.email").value("alice@example.com"))
                .andExpect(jsonPath("$.currentMembership.membershipId").value("membership-demo"))
                .andExpect(jsonPath("$.currentMembership.workspaceId").value("workspace-demo"))
                .andExpect(jsonPath("$.currentMembership.workspaceName").value("Demo Workspace"))
                .andExpect(jsonPath("$.memberships.length()").value(2));
    }

    @Test
    void authenticatedBusinessUserCanSelectOneOfTheirWorkspaceMemberships() throws Exception {
        mvc.perform(get("/api/v1/session")
                        .header("X-Workspace-Id", "workspace-growth")
                        .with(jwt().jwt(token -> token.subject(DEMO_USER_SUBJECT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentMembership.membershipId").value("membership-growth"))
                .andExpect(jsonPath("$.currentMembership.workspaceId").value("workspace-growth"));
    }

    @Test
    void authenticatedBusinessUserCannotSelectAWorkspaceWithoutMembership() throws Exception {
        mvc.perform(get("/api/v1/session")
                        .header("X-Workspace-Id", "workspace-forged")
                        .with(jwt().jwt(token -> token.subject(DEMO_USER_SUBJECT))))
                .andExpect(status().isForbidden());
    }
}

package dev.askmetric.server.conversation;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
class ConversationControllerIntegrationTest {
    private static final String ALICE = "00000000-0000-0000-0000-000000000001";

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
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void createsConversationAndRestoresPersistedMessagesInOrder() throws Exception {
        String response = mvc.perform(post("/api/v1/conversations")
                        .header("X-Workspace-Id", "workspace-demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"MRR investigation\"}")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("MRR investigation"))
                .andExpect(jsonPath("$.messages").isEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode created = objectMapper.readTree(response);
        String conversationId = created.get("conversationId").asText();

        mvc.perform(post("/api/v1/conversations/{conversationId}/messages", conversationId)
                        .header("X-Workspace-Id", "workspace-demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"  Why did MRR fall?  \"}")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.author").value("user"))
                .andExpect(jsonPath("$.sequence").value(1))
                .andExpect(jsonPath("$.content").value("Why did MRR fall?"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());

        mvc.perform(post("/api/v1/conversations/{conversationId}/messages", conversationId)
                        .header("X-Workspace-Id", "workspace-demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Use the calendar month\"}")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sequence").value(2));

        mvc.perform(get("/api/v1/conversations/{conversationId}", conversationId)
                        .header("X-Workspace-Id", "workspace-demo")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value(conversationId))
                .andExpect(jsonPath("$.messages[0].conversationId").value(conversationId))
                .andExpect(jsonPath("$.messages[0].author").value("user"))
                .andExpect(jsonPath("$.messages[0].authorSubject").value(ALICE))
                .andExpect(jsonPath("$.messages[0].sequence").value(1))
                .andExpect(jsonPath("$.messages[1].sequence").value(2))
                .andExpect(jsonPath("$.messages[1].content").value("Use the calendar month"));

        mvc.perform(get("/api/v1/conversations")
                        .header("X-Workspace-Id", "workspace-demo")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.conversationId == '%s')]", conversationId).exists());
    }

    @Test
    void hidesConversationSnapshotWhenWorkspaceHeaderDoesNotMatch() throws Exception {
        mvc.perform(get("/api/v1/conversations/conversation-demo")
                        .header("X-Workspace-Id", "workspace-growth")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isForbidden());
    }
}

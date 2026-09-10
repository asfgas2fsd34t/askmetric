package dev.askmetric.server.conversation;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * T21 验收：版本化对话摘要（记录范围与版本、不替代原始 Message），
 * 确认制的 User Memory（未确认不可见、跨工作区隔离、删除即失效）。
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class SummaryAndMemoryIntegrationTest {
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

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void summariesRecordRangeAndVersionWithoutReplacingMessages() throws Exception {
        String conversationId = createConversation("摘要验证");
        postMessage(conversationId, "你好");
        postMessage(conversationId, "AskMetric 是什么？");
        postMessage(conversationId, "你能做什么？");

        mvc.perform(post("/api/v1/conversations/{conversationId}/summaries", conversationId)
                        .header("X-Workspace-Id", "workspace-demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromSequence\":1,\"toSequence\":2}")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.fromSequence").value(1))
                .andExpect(jsonPath("$.toSequence").value(2))
                .andExpect(jsonPath("$.summaryText", org.hamcrest.Matchers.containsString("1 user: 你好")))
                .andExpect(jsonPath("$.summaryText",
                        org.hamcrest.Matchers.containsString("2 assistant:")));

        mvc.perform(post("/api/v1/conversations/{conversationId}/summaries", conversationId)
                        .header("X-Workspace-Id", "workspace-demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromSequence\":1,\"toSequence\":3}")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2));

        // 摘要与原始消息并存，消息不被替代。
        mvc.perform(get("/api/v1/conversations/{conversationId}", conversationId)
                        .header("X-Workspace-Id", "workspace-demo")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()").value(6))
                .andExpect(jsonPath("$.conversationSummaries.length()").value(2))
                .andExpect(jsonPath("$.conversationSummaries[0].version").value(2))
                .andExpect(jsonPath("$.conversationSummaries[1].version").value(1));

        mvc.perform(post("/api/v1/conversations/{conversationId}/summaries", conversationId)
                        .header("X-Workspace-Id", "workspace-demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromSequence\":3,\"toSequence\":2}")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/conversations/{conversationId}/summaries", conversationId)
                        .header("X-Workspace-Id", "workspace-demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromSequence\":9,\"toSequence\":12}")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void memoriesRequireConfirmationAndStayScopedToTheirWorkspace() throws Exception {
        // demo 工作区：待确认记忆。
        String proposedId = proposeMemory("workspace-demo", "报告默认使用美分口径展示");
        mvc.perform(get("/api/v1/user-memories")
                        .header("X-Workspace-Id", "workspace-demo")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.userMemoryId == '%s')].status", proposedId).value("PROPOSED"));

        // growth 工作区：直接确认一条记忆。
        String growthId = proposeMemory("workspace-growth", "只看 Enterprise 客户维度");
        mvc.perform(post("/api/v1/user-memories/{id}/confirmation", growthId)
                        .header("X-Workspace-Id", "workspace-growth")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.confirmedAt").isNotEmpty());

        // demo 运行：待确认条目不可见；growth 的确认条目跨工作区不可见。
        AgentRunRef run = analysisRunWithGrant();
        mvc.perform(get("/api/v1/agent-run-memories")
                        .header("X-Query-Grant", run.grant)
                        .param("runId", run.runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // 确认 demo 记忆后，同一运行可见且只有它。
        mvc.perform(post("/api/v1/user-memories/{id}/confirmation", proposedId)
                        .header("X-Workspace-Id", "workspace-demo")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/agent-run-memories")
                        .header("X-Query-Grant", run.grant)
                        .param("runId", run.runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].userMemoryId").value(proposedId))
                .andExpect(jsonPath("$[0].content", org.hamcrest.Matchers.containsString("美分口径")));

        // 重复确认被拒绝；删除后对 Agent 不可见。
        mvc.perform(post("/api/v1/user-memories/{id}/confirmation", proposedId)
                        .header("X-Workspace-Id", "workspace-demo")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/v1/user-memories/{id}", proposedId)
                        .header("X-Workspace-Id", "workspace-demo")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/agent-run-memories")
                        .header("X-Query-Grant", run.grant)
                        .param("runId", run.runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }


    @Test
    void longConversationsTruncateWithinTheDatabaseBound() throws Exception {
        String summary = ConversationService.buildSummaryText(longMessages());
        org.assertj.core.api.Assertions.assertThat(summary.length()).isLessThanOrEqualTo(4000);
        org.assertj.core.api.Assertions.assertThat(summary).endsWith("（超出摘要边界，已截断）");
    }

    private static java.util.List<ConversationMessage> longMessages() {
        java.util.List<ConversationMessage> messages = new java.util.ArrayList<>();
        for (int index = 1; index <= 80; index++) {
            ConversationMessage message = new ConversationMessage();
            message.setSequence(index);
            message.setAuthor("user");
            message.setContent("x".repeat(90));
            messages.add(message);
        }
        return messages;
    }

    private String proposeMemory(String workspaceId, String content) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/user-memories")
                        .header("X-Workspace-Id", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"%s\"}".formatted(content))
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("userMemoryId").asText();
    }

    /** 创建一个带查询授权的分析运行（不需要真实查询）。 */
    private AgentRunRef analysisRunWithGrant() throws Exception {
        String conversationId = createConversation("记忆可见性");
        postMessage(conversationId, "为什么本月 MRR 下降？");
        postMessage(conversationId, "使用标准 MRR v3 口径");
        String response = postMessage(conversationId, "继续下钻 MRR 降幅来源");
        String runId = objectMapper.readTree(response).at("/agentRun/runId").asText();
        String grant = objectMapper.readTree(jdbcTemplate.queryForObject(
                        "select payload from agent_run_outbox where run_id = ?", String.class, runId))
                .get("queryGrant").asText();
        return new AgentRunRef(runId, grant);
    }

    private String createConversation(String title) throws Exception {
        String response = mvc.perform(post("/api/v1/conversations")
                        .header("X-Workspace-Id", "workspace-demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"%s\"}".formatted(title))
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("conversationId").asText();
    }

    private String postMessage(String conversationId, String content) throws Exception {
        return mvc.perform(post("/api/v1/conversations/{conversationId}/messages", conversationId)
                        .header("X-Workspace-Id", "workspace-demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"%s\"}".formatted(content))
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private record AgentRunRef(String runId, String grant) {
    }
}

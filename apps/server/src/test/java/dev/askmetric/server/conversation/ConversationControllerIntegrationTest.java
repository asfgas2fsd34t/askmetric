package dev.askmetric.server.conversation;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.askmetric.server.agent.AgentRunEvent;
import dev.askmetric.server.agent.AgentRunEventSource;
import dev.askmetric.server.agent.AgentRunEventType;
import dev.askmetric.server.agent.AgentRunService;
import java.time.Instant;
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

    @Autowired
    AgentRunService agentRunService;

    @Test
    void createsConversationAndRestoresPersistedMessagesInOrder() throws Exception {
        String response = mvc.perform(post("/api/v1/conversations")
                        .header("X-Workspace-Id", "workspace-demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"产品咨询\"}")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("产品咨询"))
                .andExpect(jsonPath("$.messages").isEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode created = objectMapper.readTree(response);
        String conversationId = created.get("conversationId").asText();

        mvc.perform(post("/api/v1/conversations/{conversationId}/messages", conversationId)
                        .header("X-Workspace-Id", "workspace-demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"  AskMetric 是什么？  \"}")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userMessage.author").value("user"))
                .andExpect(jsonPath("$.userMessage.sequence").value(1))
                .andExpect(jsonPath("$.userMessage.content").value("AskMetric 是什么？"))
                .andExpect(jsonPath("$.assistantMessage.author").value("assistant"))
                .andExpect(jsonPath("$.assistantMessage.sequence").value(2))
                .andExpect(jsonPath("$.assistantMessage.content").value(
                        "AskMetric 将业务问题转化为可审计分析，并在需要时生成独立 HTML 报告。"))
                .andExpect(jsonPath("$.agentRun.intentRoute").value("chat"));

        mvc.perform(post("/api/v1/conversations/{conversationId}/messages", conversationId)
                        .header("X-Workspace-Id", "workspace-demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"你能做什么？\"}")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userMessage.sequence").value(3))
                .andExpect(jsonPath("$.assistantMessage.sequence").value(4));

        mvc.perform(get("/api/v1/conversations/{conversationId}", conversationId)
                        .header("X-Workspace-Id", "workspace-demo")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value(conversationId))
                .andExpect(jsonPath("$.messages[0].conversationId").value(conversationId))
                .andExpect(jsonPath("$.messages[0].author").value("user"))
                .andExpect(jsonPath("$.messages[0].authorSubject").value(ALICE))
                .andExpect(jsonPath("$.messages[0].sequence").value(1))
                .andExpect(jsonPath("$.messages[1].author").value("assistant"))
                .andExpect(jsonPath("$.messages[2].sequence").value(3))
                .andExpect(jsonPath("$.messages[2].content").value("你能做什么？"))
                .andExpect(jsonPath("$.messages[3].author").value("assistant"))
                .andExpect(jsonPath("$.agentRuns.length()").value(2));

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

    @Test
    void startsAnAgentRunWhenTheMembershipCanCreateAMessage() throws Exception {
        mvc.perform(post("/api/v1/conversations/conversation-growth/messages")
                        .header("X-Workspace-Id", "workspace-growth")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"你好\"}")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.agentRun.intentRoute").value("chat"));
    }

    @Test
    void replaysTheFirstResponseForAnIdempotentMessageRetry() throws Exception {
        String key = "message-retry-001";
        String first = mvc.perform(post("/api/v1/conversations/conversation-demo/messages")
                        .header("X-Workspace-Id", "workspace-demo")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"你好\"}")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String replay = mvc.perform(post("/api/v1/conversations/conversation-demo/messages")
                        .header("X-Workspace-Id", "workspace-demo")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"你好\"}")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode firstJson = objectMapper.readTree(first);
        JsonNode replayJson = objectMapper.readTree(replay);
        org.assertj.core.api.Assertions.assertThat(replayJson.at("/userMessage/messageId").asText())
                .isEqualTo(firstJson.at("/userMessage/messageId").asText());
        org.assertj.core.api.Assertions.assertThat(replayJson.at("/agentRun/runId").asText())
                .isEqualTo(firstJson.at("/agentRun/runId").asText());

        mvc.perform(get("/api/v1/conversations/conversation-demo")
                        .header("X-Workspace-Id", "workspace-demo")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()", org.hamcrest.Matchers.is(2)))
                .andExpect(jsonPath("$.agentRuns.length()", org.hamcrest.Matchers.is(1)));
    }

    @Test
    void rejectsAnIdempotencyKeyReusedForDifferentMessageContent() throws Exception {
        String key = "message-conflict-001";
        mvc.perform(post("/api/v1/conversations/conversation-demo/messages")
                        .header("X-Workspace-Id", "workspace-demo")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"你好\"}")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/conversations/conversation-demo/messages")
                        .header("X-Workspace-Id", "workspace-demo")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"你是谁？\"}")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Idempotency-Key 已用于不同的消息内容"));
    }

    @Test
    void completesAGreetingAsChatWithoutCreatingAnAnalysisTask() throws Exception {
        mvc.perform(post("/api/v1/conversations/conversation-demo/messages")
                        .header("X-Workspace-Id", "workspace-demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"你好\"}")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userMessage.author").value("user"))
                .andExpect(jsonPath("$.assistantMessage.author").value("assistant"))
                .andExpect(jsonPath("$.assistantMessage.content").value("你好，我是 AskMetric。你可以向我询问业务指标、数据口径或分析目标。"))
                .andExpect(jsonPath("$.agentRun.intentRoute").value("chat"))
                .andExpect(jsonPath("$.agentRun.intentConfidence").value(1.0))
                .andExpect(jsonPath("$.agentRun.auditEvents[2].eventType").value("agent.run.completed"))
                .andExpect(jsonPath("$.agentRun.auditEvents.length()").value(3));

        mvc.perform(get("/api/v1/conversations/conversation-demo")
                        .header("X-Workspace-Id", "workspace-demo")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].author").value("user"))
                .andExpect(jsonPath("$.messages[1].author").value("assistant"))
                .andExpect(jsonPath("$.agentRuns").isNotEmpty())
                .andExpect(jsonPath("$.agentRuns[0].intentRoute").value("chat"))
                .andExpect(jsonPath("$.analysisTasks").isEmpty())
                .andExpect(jsonPath("$.agentRuns[0].auditEvents[2].eventType").value("agent.run.completed"));
    }

    @Test
    void completesAProductQuestionAsChatWithoutCreatingAnAnalysisTask() throws Exception {
        mvc.perform(post("/api/v1/conversations/conversation-demo/messages")
                        .header("X-Workspace-Id", "workspace-demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"你是谁？\"}")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assistantMessage.content").value(
                        "AskMetric 将业务问题转化为可审计分析，并在需要时生成独立 HTML 报告。"))
                .andExpect(jsonPath("$.agentRun.intentRoute").value("chat"))
                .andExpect(jsonPath("$.agentRun.auditEvents[2].eventType").value("agent.run.completed"));
    }

    @Test
    void routesAnMrrQuestionToANewPersistedAnalysisTask() throws Exception {
        String createdConversation = mvc.perform(post("/api/v1/conversations")
                        .header("X-Workspace-Id", "workspace-demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"MRR 调查\"}")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String conversationId = objectMapper.readTree(createdConversation).get("conversationId").asText();

        String accepted = mvc.perform(post("/api/v1/conversations/{conversationId}/messages", conversationId)
                        .header("X-Workspace-Id", "workspace-demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"为什么本月 MRR 下降？\"}")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userMessage.content").value("为什么本月 MRR 下降？"))
                .andExpect(jsonPath("$.assistantMessage").doesNotExist())
                .andExpect(jsonPath("$.agentRun.intentRoute").value("analysis"))
                .andExpect(jsonPath("$.agentRun.intentConfidence").value(0.95))
                .andExpect(jsonPath("$.analysisTask.goal").value("为什么本月 MRR 下降？"))
                .andExpect(jsonPath("$.analysisTask.status").value("active"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode result = objectMapper.readTree(accepted);
        String runId = result.at("/agentRun/runId").asText();
        String analysisTaskId = result.at("/analysisTask/analysisTaskId").asText();
        org.assertj.core.api.Assertions.assertThat(result.at("/analysisTask/sourceAgentRunId").asText())
                .isEqualTo(runId);
        org.assertj.core.api.Assertions.assertThat(result.at("/agentRun/analysisTaskId").asText())
                .isEqualTo(analysisTaskId);

        mvc.perform(get("/api/v1/conversations/{conversationId}", conversationId)
                        .header("X-Workspace-Id", "workspace-demo")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentRuns[0].intentRoute").value("analysis"))
                .andExpect(jsonPath("$.agentRuns[0].intentConfidence").value(0.95))
                .andExpect(jsonPath("$.analysisTasks[0].analysisTaskId").value(analysisTaskId))
                .andExpect(jsonPath("$.analysisTasks[0].sourceAgentRunId").value(runId))
                .andExpect(jsonPath("$.analysisTasks[0].goal").value("为什么本月 MRR 下降？"));

        mvc.perform(post("/api/v1/conversations/{conversationId}/messages", conversationId)
                        .header("X-Workspace-Id", "workspace-demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"再分析一下 MRR 趋势\"}")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.agentRun.intentRoute").value("analysis"))
                .andExpect(jsonPath("$.agentRun.intentConfidence").value(0.95))
                .andExpect(jsonPath("$.analysisTask").doesNotExist())
                .andExpect(jsonPath("$.assistantMessage.content").value(
                        "当前对话已有活动分析任务。请说明要继续当前目标，还是切换到新的分析目标。"));

        mvc.perform(get("/api/v1/conversations/{conversationId}", conversationId)
                        .header("X-Workspace-Id", "workspace-demo")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisTasks.length()").value(1));
    }

    @Test
    void continuesTheActiveAnalysisTaskWithoutCreatingAnotherTask() throws Exception {
        String conversationId = createConversation("MRR 调查");
        String analysisTaskId = analysisTaskIdFor(conversationId, "为什么本月 MRR 下降？");

        mvc.perform(post("/api/v1/conversations/{conversationId}/messages", conversationId)
                        .header("X-Workspace-Id", "workspace-demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"继续按 Enterprise 客户拆分\"}")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.agentRun.intentRoute").value("analysis"))
                .andExpect(jsonPath("$.agentRun.intentConfidence").value(1.0))
                .andExpect(jsonPath("$.agentRun.analysisTaskId").value(analysisTaskId))
                .andExpect(jsonPath("$.analysisTask").doesNotExist());

        mvc.perform(get("/api/v1/conversations/{conversationId}", conversationId)
                        .header("X-Workspace-Id", "workspace-demo")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisTasks.length()").value(1))
                .andExpect(jsonPath("$.analysisTasks[0].analysisTaskId").value(analysisTaskId))
                .andExpect(jsonPath("$.analysisTasks[0].status").value("active"))
                .andExpect(jsonPath("$.agentRuns[1].analysisTaskId").value(analysisTaskId));
    }

    @Test
    void switchesToANewAnalysisTaskAndKeepsThePreviousTaskVisible() throws Exception {
        String conversationId = createConversation("经营指标调查");
        String previousTaskId = analysisTaskIdFor(conversationId, "为什么本月 MRR 下降？");

        String response = mvc.perform(post("/api/v1/conversations/{conversationId}/messages", conversationId)
                        .header("X-Workspace-Id", "workspace-demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"切换到调查客户流失率趋势\"}")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.agentRun.intentRoute").value("analysis"))
                .andExpect(jsonPath("$.agentRun.intentConfidence").value(1.0))
                .andExpect(jsonPath("$.analysisTask.goal").value("切换到调查客户流失率趋势"))
                .andExpect(jsonPath("$.analysisTask.status").value("active"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String currentTaskId = objectMapper.readTree(response).at("/analysisTask/analysisTaskId").asText();

        mvc.perform(get("/api/v1/conversations/{conversationId}", conversationId)
                        .header("X-Workspace-Id", "workspace-demo")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisTasks.length()").value(2))
                .andExpect(jsonPath("$.analysisTasks[0].analysisTaskId").value(previousTaskId))
                .andExpect(jsonPath("$.analysisTasks[0].status").value("waiting_for_input"))
                .andExpect(jsonPath("$.analysisTasks[1].analysisTaskId").value(currentTaskId))
                .andExpect(jsonPath("$.analysisTasks[1].status").value("active"))
                .andExpect(jsonPath("$.agentRuns[1].analysisTaskId").value(currentTaskId));
    }

    @Test
    void asksForAnAnalysisGoalWhenAContinuationCommandHasNoActiveTask() throws Exception {
        String conversationId = createConversation("新的调查");

        mvc.perform(post("/api/v1/conversations/{conversationId}/messages", conversationId)
                        .header("X-Workspace-Id", "workspace-demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"继续按 Enterprise 客户拆分\"}")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.agentRun.intentRoute").value("analysis"))
                .andExpect(jsonPath("$.agentRun.intentConfidence").value(1.0))
                .andExpect(jsonPath("$.analysisTask").doesNotExist())
                .andExpect(jsonPath("$.assistantMessage.content").value(
                        "当前对话没有活动分析任务。请先描述要调查的分析目标。"));

        mvc.perform(get("/api/v1/conversations/{conversationId}", conversationId)
                        .header("X-Workspace-Id", "workspace-demo")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisTasks").isEmpty());
    }

    @Test
    void asksForAnAnalysisGoalWhenASwitchCommandHasNoTarget() throws Exception {
        String conversationId = createConversation("MRR 调查");
        String activeTaskId = analysisTaskIdFor(conversationId, "为什么本月 MRR 下降？");

        mvc.perform(post("/api/v1/conversations/{conversationId}/messages", conversationId)
                        .header("X-Workspace-Id", "workspace-demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"切换到\"}")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.analysisTask").doesNotExist())
                .andExpect(jsonPath("$.assistantMessage.content").value(
                        "当前对话已有活动分析任务。请说明要继续当前目标，还是切换到新的分析目标。"));

        mvc.perform(get("/api/v1/conversations/{conversationId}", conversationId)
                        .header("X-Workspace-Id", "workspace-demo")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisTasks.length()").value(1))
                .andExpect(jsonPath("$.analysisTasks[0].analysisTaskId").value(activeTaskId))
                .andExpect(jsonPath("$.analysisTasks[0].status").value("active"));
    }

    @Test
    void cancelsAnAnalysisRunAndRejectsItsLateCompletion() throws Exception {
        String conversationId = createConversation("停止分析");
        String accepted = mvc.perform(post("/api/v1/conversations/{conversationId}/messages", conversationId)
                        .header("X-Workspace-Id", "workspace-demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"为什么本月 MRR 下降？\"}")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String runId = objectMapper.readTree(accepted).at("/agentRun/runId").asText();

        String cancelled = mvc.perform(post(
                            "/api/v1/conversations/{conversationId}/runs/{runId}/cancel", conversationId, runId)
                        .header("X-Workspace-Id", "workspace-demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"问题不再需要分析\"}")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventType").value("agent.run.cancelled"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("分析任务已创建")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("问题不再需要分析")))
                .andReturn()
                .getResponse()
                .getContentAsString();
        long cancelSequence = objectMapper.readTree(cancelled).get("sequence").asLong();

        agentRunService.acceptEvent(new AgentRunEvent(
                "late-completed-" + runId,
                1,
                AgentRunEventType.COMPLETED,
                cancelSequence + 1,
                Instant.now(),
                conversationId,
                runId,
                "迟到的成功终态",
                AgentRunEventSource.PYTHON));

        mvc.perform(get("/api/v1/conversations/{conversationId}", conversationId)
                        .header("X-Workspace-Id", "workspace-demo")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisTasks[0].status").value("cancelled"))
                .andExpect(jsonPath("$.agentRuns[0].auditEvents.length()").value(3))
                .andExpect(jsonPath("$.agentRuns[0].auditEvents[0].eventType").value("agent.run.accepted"))
                .andExpect(jsonPath("$.agentRuns[0].auditEvents[1].eventType").value("agent.run.progress"))
                .andExpect(jsonPath("$.agentRuns[0].auditEvents[2].eventType").value("agent.run.cancelled"));
    }

    private String createConversation(String title) throws Exception {
        String response = mvc.perform(post("/api/v1/conversations")
                        .header("X-Workspace-Id", "workspace-demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"%s\"}".formatted(title))
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).get("conversationId").asText();
    }

    private String analysisTaskIdFor(String conversationId, String content) throws Exception {
        String response = mvc.perform(post("/api/v1/conversations/{conversationId}/messages", conversationId)
                        .header("X-Workspace-Id", "workspace-demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"%s\"}".formatted(content))
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.analysisTask.status").value("active"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).at("/analysisTask/analysisTaskId").asText();
    }
}

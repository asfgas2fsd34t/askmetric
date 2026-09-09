package dev.askmetric.server.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import dev.askmetric.server.agent.AgentRunFinding;
import dev.askmetric.server.agent.AgentRunService;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * T18 MRR 下钻验收：Agent 持 Java 签发的查询授权走 Query Gateway 取证，
 * 产出的已验证发现与确定性 Ground Truth 一致，且证据不足时不伪造结论。
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class MrrDrilldownIntegrationTest {
    private static final String ALICE = "00000000-0000-0000-0000-000000000001";
    private static final String BASELINE_SQL =
            "select month_start, ending_mrr_cents from demo_warehouse.monthly_mrr order by month_start";
    private static final String JUNE_EVENTS_SQL = """
            select segment.segment_code, event.plan_code, event.event_type, event.mrr_delta_cents
            from demo_warehouse.subscription_event event
            join demo_warehouse.customer_account account on account.customer_id = event.customer_id
            join demo_warehouse.customer_segment segment on segment.segment_code = account.segment_code
            where event.event_date >= '2025-06-01' and event.event_date < '2025-07-01'
            order by event.mrr_delta_cents
            """;

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
        // 受治理查询数据源指向同一容器内的 Demo Warehouse Schema。
        registry.add("askmetric.query.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("askmetric.query.datasource.username", () -> "askmetric_app");
        registry.add("askmetric.query.datasource.password", () -> "askmetric_app");
    }

    @BeforeAll
    static void seedDemoWarehouse() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            ScriptUtils.executeSqlScript(
                    connection,
                    new FileSystemResource(
                            Paths.get("..", "..", "infra", "demo-warehouse", "reset.sql").toFile()));
            ScriptUtils.executeSqlScript(connection, new org.springframework.core.io.ByteArrayResource(
                    ("GRANT USAGE ON SCHEMA demo_warehouse TO askmetric_app;"
                            + "GRANT SELECT ON ALL TABLES IN SCHEMA demo_warehouse TO askmetric_app;")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    AgentRunService agentRunService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void executesGovernedDrilldownQueriesForAnAuthorizedAnalysisRun() throws Exception {
        String conversationId = createConversation("MRR 下钻");
        postMessage(conversationId, "为什么本月 MRR 下降？");
        postMessage(conversationId, "使用标准 MRR v3 口径");
        String drilldown = postMessage(conversationId, "继续下钻 MRR 降幅来源");
        String runId = objectMapper.readTree(drilldown).at("/agentRun/runId").asText();

        String outboxPayload = jdbcTemplate.queryForObject(
                "select payload from agent_run_outbox where run_id = ?", String.class, runId);
        JsonNode request = objectMapper.readTree(outboxPayload);
        assertThat(request.get("metricDefinitionVersionId").asText()).isEqualTo("metric_definition_mrr_v3");
        String grant = request.get("queryGrant").asText();
        assertThat(grant).isNotBlank();

        String baseline = mvc.perform(post("/api/v1/agent-run-queries")
                        .header("X-Query-Grant", grant)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runId\":\"%s\",\"sql\":\"%s\"}".formatted(runId, BASELINE_SQL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowCount").value(6))
                .andExpect(jsonPath("$.rows[4].month_start").value("2025-05-01"))
                .andExpect(jsonPath("$.rows[4].ending_mrr_cents").value(480000))
                .andExpect(jsonPath("$.rows[5].month_start").value("2025-06-01"))
                .andExpect(jsonPath("$.rows[5].ending_mrr_cents").value(300000))
                .andExpect(jsonPath("$.evidenceSnapshotId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        int drop = computeJuneDrop(baseline);
        assertThat(drop).isEqualTo(180000);

        String juneEvents = mvc.perform(post("/api/v1/agent-run-queries")
                        .header("X-Query-Grant", grant)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runId\":\"%s\",\"sql\":\"%s\"}".formatted(
                                runId, JUNE_EVENTS_SQL.replace("\n", " ").replace("\"", "\\\""))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowCount").value(3))
                .andReturn().getResponse().getContentAsString();
        assertThat(grossChurn(juneEvents)).isEqualTo(-300000);
        assertThat(juneEvents).contains("ENTERPRISE");

        mvc.perform(post("/api/v1/agent-run-queries")
                        .header("X-Query-Grant", "not-a-grant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runId\":\"%s\",\"sql\":\"%s\"}".formatted(runId, BASELINE_SQL)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Query grant is invalid or expired"));

        mvc.perform(post("/api/v1/agent-run-queries")
                        .header("X-Query-Grant", grant)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runId\":\"%s\",\"sql\":\"select month_start from public.monthly_mrr\"}"
                                .formatted(runId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", org.hamcrest.Matchers.containsString("表未登记")));
    }

    @Test
    void persistsAVerifiedFindingThatMatchesTheDeterministicGroundTruth() throws Exception {
        String conversationId = createConversation("MRR 已验证发现");
        postMessage(conversationId, "为什么本月 MRR 下降？");
        postMessage(conversationId, "使用标准 MRR v3 口径");
        String drilldown = postMessage(conversationId, "继续下钻 MRR 降幅来源");
        String runId = objectMapper.readTree(drilldown).at("/agentRun/runId").asText();
        String grant = objectMapper.readTree(jdbcTemplate.queryForObject(
                        "select payload from agent_run_outbox where run_id = ?", String.class, runId))
                .get("queryGrant").asText();

        String baseline = mvc.perform(post("/api/v1/agent-run-queries")
                        .header("X-Query-Grant", grant)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runId\":\"%s\",\"sql\":\"%s\"}".formatted(runId, BASELINE_SQL)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String evidenceSnapshotId = objectMapper.readTree(baseline)
                .get("evidenceSnapshotId").asText();

        String conclusion = "已验证：2025 年 6 月 MRR 下降 180000 美分（-37.50%），"
                + "主要贡献来自 ENTERPRISE 分层 ENTERPRISE 套餐客户流失 -300000 美分，"
                + "同期新增 +120000 美分部分抵消。";

        // 引用不属于本运行的 Evidence Snapshot 的发现必须整体拒绝，事件与发现一并回滚。
        assertThatThrownBy(() -> agentRunService.acceptEvent(new AgentRunEvent(
                "foreign-finding-" + runId,
                1,
                AgentRunEventType.FINDING,
                3,
                Instant.now(),
                conversationId,
                runId,
                conclusion,
                AgentRunEventSource.PYTHON,
                new AgentRunFinding(
                        conclusion,
                        "metric_definition_mrr_v3",
                        true,
                        List.of("evidence_snapshot_of_another_run"),
                        List.of(),
                        List.of(),
                        List.of()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Analysis Finding was not accepted");

        agentRunService.acceptEvent(new AgentRunEvent(
                "finding-" + runId,
                1,
                AgentRunEventType.FINDING,
                3,
                Instant.now(),
                conversationId,
                runId,
                conclusion,
                AgentRunEventSource.PYTHON,
                new AgentRunFinding(
                        conclusion,
                        "metric_definition_mrr_v3",
                        true,
                        List.of(evidenceSnapshotId),
                        List.of("月末 MRR 由订阅事件累计重建"),
                        List.of(),
                        List.of())));
        agentRunService.acceptEvent(new AgentRunEvent(
                "drilldown-completed-" + runId,
                1,
                AgentRunEventType.COMPLETED,
                4,
                Instant.now(),
                conversationId,
                runId,
                "下钻完成，已验证发现已产出",
                AgentRunEventSource.PYTHON,
                null));

        mvc.perform(get("/api/v1/conversations/{conversationId}", conversationId)
                        .header("X-Workspace-Id", "workspace-demo")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisFindings.length()").value(1))
                .andExpect(jsonPath("$.analysisFindings[0].verified").value(true))
                .andExpect(jsonPath("$.analysisFindings[0].metricDefinitionVersionId")
                        .value("metric_definition_mrr_v3"))
                .andExpect(jsonPath("$.analysisFindings[0].evidenceSnapshotIds[0]").value(evidenceSnapshotId))
                .andExpect(jsonPath("$.analysisFindings[0].conclusion",
                        org.hamcrest.Matchers.containsString("180000")))
                .andExpect(jsonPath("$.analysisFindings[0].assumptions[0]",
                        org.hamcrest.Matchers.containsString("累计重建")))
                .andExpect(jsonPath("$.agentRuns[2].auditEvents[2].eventType").value("agent.run.finding"))
                .andExpect(jsonPath("$.agentRuns[2].auditEvents[3].eventType").value("agent.run.completed"));
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
        MvcResult result = mvc.perform(post("/api/v1/conversations/{conversationId}/messages", conversationId)
                        .header("X-Workspace-Id", "workspace-demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"%s\"}".formatted(content))
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isCreated())
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    /** 从基线查询结果计算 5 月至 6 月的 MRR 降幅，验证确定性 Ground Truth。 */
    private int computeJuneDrop(String queryResultJson) throws Exception {
        JsonNode rows = objectMapper.readTree(queryResultJson).get("rows");
        int may = rows.get(4).get("ending_mrr_cents").asInt();
        int june = rows.get(5).get("ending_mrr_cents").asInt();
        return may - june;
    }

    /** 汇总 6 月流失事件金额，验证主要降幅贡献来自分层套餐客户流失。 */
    private int grossChurn(String queryResultJson) throws Exception {
        int churn = 0;
        for (JsonNode row : objectMapper.readTree(queryResultJson).get("rows")) {
            if ("CHURN".equals(row.get("event_type").asText())) {
                churn += row.get("mrr_delta_cents").asInt();
            }
        }
        return churn;
    }
}

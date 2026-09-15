package dev.askmetric.server.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import org.junit.jupiter.api.BeforeEach;
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
 * T22 验收：操作提案由 Agent 从运行派生、参数从受治理口径快照推导且不可变，
 * 创建需发起者显式确认，Agent 没有任何执行路径。
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class ActionProposalIntegrationTest {
    private static final String ALICE = "00000000-0000-0000-0000-000000000001";
    private static final String CAROL = "00000000-0000-0000-0000-000000000003";

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

    @BeforeEach
    void seedSecondDemoMember() throws Exception {
        // 应用账号对 workspace_membership 只有 SELECT；以迁移账号补一名非发起者成员。
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            connection.createStatement().execute("""
                    insert into workspace_membership (membership_id, workspace_id, user_subject, permissions)
                    values ('membership-proposal-carol', 'workspace-demo',
                            '00000000-0000-0000-0000-000000000003',
                            ARRAY['VIEW_WORKSPACE', 'VIEW_AGENT_RUN', 'CREATE_MESSAGE'])
                    on conflict (membership_id) do nothing
                    """);
        }
    }

    @org.junit.jupiter.api.AfterEach
    void restoreDemoPolicy() throws Exception {
        updatePolicyAsOwner("""
                update workspace_policy
                set policy_version = 1, requires_separate_approver = false
                where workspace_id = 'workspace-demo'
                """);
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            connection.createStatement().execute("""
                    update workspace_membership
                    set permissions = ARRAY['VIEW_WORKSPACE', 'VIEW_AGENT_RUN', 'CREATE_MESSAGE']
                    where workspace_id = 'workspace-demo'
                      and user_subject = '00000000-0000-0000-0000-000000000003'
                    """);
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void agentSubmitsDraftFromCustomCaliberRunAndInitiatorConfirmsIt() throws Exception {
        String conversationId = createConversation("提案确认");
        String customRunId = prepareCustomCaliberDrilldownRun(conversationId, "提案确认的自定义规则");

        String grant = grantFor(customRunId);
        MvcResult submitted = mvc.perform(post("/api/v1/agent-run-proposals")
                        .header("X-Query-Grant", grant)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runId\":\"%s\",\"actionType\":\"PROMOTE_CUSTOM_CALIBER\"}"
                                .formatted(customRunId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.actionType").value("PROMOTE_CUSTOM_CALIBER"))
                .andExpect(jsonPath("$.status").value("AWAITING_CONFIRMATION"))
                .andExpect(jsonPath("$.metricKey").value("mrr"))
                .andExpect(jsonPath("$.calculationRule").value("提案确认的自定义规则"))
                .andExpect(jsonPath("$.timeBoundary").isNotEmpty())
                .andExpect(jsonPath("$.exclusions").isNotEmpty())
                .andExpect(jsonPath("$.policyVersion").value(1))
                .andExpect(jsonPath("$.idempotencyKey").value("PROMOTE_CUSTOM_CALIBER:" + customRunId))
                .andExpect(jsonPath("$.proposedBy").value(ALICE))
                .andExpect(jsonPath("$.confirmedAt").doesNotExist())
                .andReturn();
        String proposalId = objectMapper.readTree(submitted.getResponse().getContentAsString())
                .get("actionProposalId").asText();

        // 重复提交同一（运行、操作类型）重放首次提案，不产生第二个草案。
        mvc.perform(post("/api/v1/agent-run-proposals")
                        .header("X-Query-Grant", grant)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runId\":\"%s\",\"actionType\":\"PROMOTE_CUSTOM_CALIBER\"}"
                                .formatted(customRunId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.actionProposalId").value(proposalId));

        // 未经发起者确认的提案不会进入等待审批；确认是显式动作且只能由发起者完成，
        // carol 是同工作区成员但不是发起者，确认被发起者守卫拒绝。
        mvc.perform(post("/api/v1/action-proposals/{id}/confirmation", proposalId)
                        .header("X-Workspace-Id", "workspace-demo")
                        .with(jwt().jwt(token -> token.subject(CAROL))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/action-proposals/{id}/confirmation", proposalId)
                        .header("X-Workspace-Id", "workspace-demo")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_APPROVAL"))
                .andExpect(jsonPath("$.confirmedAt").isNotEmpty())
                // 确认只推进状态，参数与幂等键保持不变。
                .andExpect(jsonPath("$.idempotencyKey").value("PROMOTE_CUSTOM_CALIBER:" + customRunId))
                .andExpect(jsonPath("$.calculationRule").value("提案确认的自定义规则"));

        // 重复确认没有效果，也不会把状态推回任何执行路径。
        mvc.perform(post("/api/v1/action-proposals/{id}/confirmation", proposalId)
                        .header("X-Workspace-Id", "workspace-demo")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isBadRequest());

        // 提案随快照展示全部要素，供审批前检查。
        mvc.perform(get("/api/v1/conversations/{conversationId}", conversationId)
                        .header("X-Workspace-Id", "workspace-demo")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actionProposals.length()").value(1))
                .andExpect(jsonPath("$.actionProposals[0].actionProposalId").value(proposalId))
                .andExpect(jsonPath("$.actionProposals[0].status").value("AWAITING_APPROVAL"))
                .andExpect(jsonPath("$.actionProposals[0].sourceAgentRunId").value(customRunId))
                .andExpect(jsonPath("$.actionProposals[0].policyVersion").value(1));
    }

    @Test
    void standardCaliberRunsCannotProduceProposalsAndGrantsAreEnforced() throws Exception {
        String conversationId = createConversation("标准口径不出提案");
        postMessage(conversationId, "为什么本月 MRR 下降？");
        postMessage(conversationId, "使用标准 MRR v3 口径");
        String runId = runIdOf(postMessage(conversationId, "继续下钻 MRR 降幅来源"));

        mvc.perform(post("/api/v1/agent-run-proposals")
                        .header("X-Query-Grant", "not-a-grant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runId\":\"%s\",\"actionType\":\"PROMOTE_CUSTOM_CALIBER\"}".formatted(runId)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/agent-run-proposals")
                        .header("X-Query-Grant", grantFor(runId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runId\":\"%s\",\"actionType\":\"PROMOTE_CUSTOM_CALIBER\"}".formatted(runId)))
                .andExpect(status().isBadRequest());
        // ADR-0009：Agent 只能提交升级提案；修订标准口径属于后续票的人工创建路径。
        mvc.perform(post("/api/v1/agent-run-proposals")
                        .header("X-Query-Grant", grantFor(runId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runId\":\"%s\",\"actionType\":\"REVISE_STANDARD_CALIBER\"}".formatted(runId)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/agent-run-proposals")
                        .header("X-Query-Grant", grantFor(runId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runId\":\"%s\",\"actionType\":\"NOT_A_TYPE\"}".formatted(runId)))
                .andExpect(status().isBadRequest());


        // 请求事件携带口径作用域，Python 据此判断是否提议。
        JsonNode request = objectMapper.readTree(
                jdbcTemplate.queryForObject("select payload from agent_run_outbox where run_id = ?",
                        String.class, runId));
        assertThat(request.get("metricDefinitionScope").asText()).isEqualTo("STANDARD");
    }

    @Test
    void aNewerProposalForTheSameTaskSupersedesTheOldWithoutTouchingItsParameters() throws Exception {
        String conversationId = createConversation("修改即新提案");
        String firstRunId = prepareCustomCaliberDrilldownRun(conversationId, "第一版自定义规则");
        String firstProposalId = submitProposal(firstRunId);
        mvc.perform(post("/api/v1/action-proposals/{id}/confirmation", firstProposalId)
                        .header("X-Workspace-Id", "workspace-demo")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isOk());

        // 同一任务出现第二个提案（新一轮自定义口径运行）：旧提案整体让位。
        String secondRunId = prepareContinuedCustomCaliberDrilldownRun(conversationId, "第二版自定义规则");
        String secondProposalId = submitProposal(secondRunId);

        mvc.perform(get("/api/v1/conversations/{conversationId}", conversationId)
                        .header("X-Workspace-Id", "workspace-demo")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actionProposals.length()").value(2))
                .andExpect(jsonPath("$.actionProposals[?(@.actionProposalId == '" + firstProposalId + "')].status")
                        .value("SUPERSEDED"))
                .andExpect(jsonPath("$.actionProposals[?(@.actionProposalId == '" + firstProposalId + "')].supersededBy")
                        .value(secondProposalId))
                // 被取代提案的参数保持创建时的快照，未被修改。
                .andExpect(jsonPath("$.actionProposals[?(@.actionProposalId == '" + firstProposalId + "')].calculationRule")
                        .value("第一版自定义规则"))
                .andExpect(jsonPath("$.actionProposals[?(@.actionProposalId == '" + secondProposalId + "')].calculationRule")
                        .value("第二版自定义规则"));

        // 被取代的提案不能再被确认，也不会进入等待审批。
        mvc.perform(post("/api/v1/action-proposals/{id}/confirmation", firstProposalId)
                        .header("X-Workspace-Id", "workspace-demo")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void initiatorCanDiscardADraftAndItNeverReachesApproval() throws Exception {
        String conversationId = createConversation("放弃提案");
        String runId = prepareCustomCaliberDrilldownRun(conversationId, "将被放弃的自定义规则");
        String proposalId = submitProposal(runId);

        mvc.perform(post("/api/v1/action-proposals/{id}/discard", proposalId)
                        .header("X-Workspace-Id", "workspace-demo")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISCARDED"));
        // 放弃后不能再确认。
        mvc.perform(post("/api/v1/action-proposals/{id}/confirmation", proposalId)
                        .header("X-Workspace-Id", "workspace-demo")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void proposalsStayScopedToTheirWorkspace() throws Exception {
        String conversationId = createConversation("跨工作区隔离");
        String runId = prepareCustomCaliberDrilldownRun(conversationId, "隔离验证的自定义规则");
        String proposalId = submitProposal(runId);

        // 提案更新按工作区收窄：即使拥有 growth 的发消息权限，也改不到 demo 的提案。
        mvc.perform(post("/api/v1/action-proposals/{id}/confirmation", proposalId)
                        .header("X-Workspace-Id", "workspace-growth")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isBadRequest());
        // 会话快照也按工作区隔离：growth 视角看不到 demo 的对话与提案。
        mvc.perform(get("/api/v1/conversations/{conversationId}", conversationId)
                        .header("X-Workspace-Id", "workspace-growth")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isForbidden());
    }

    /** 走完整业务链路得到一个绑定自定义口径的下钻运行：建任务→自定义口径→下钻消息。 */

    @Test
    void governanceMemberApprovesConfirmedProposalWithDecisionDetails() throws Exception {
        String conversationId = createConversation("审批通过");
        String runId = prepareCustomCaliberDrilldownRun(conversationId, "只统计月末有效订阅");
        String proposalId = submitProposal(runId);
        postConfirm(proposalId);

        // demo 策略宽松：发起者可以自批（confirm-to-record）。
        mvc.perform(post("/api/v1/action-proposals/{id}/approval", proposalId)
                        .header("X-Workspace-Id", "workspace-demo")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decidedBy").value(ALICE))
                .andExpect(jsonPath("$.decidedAt").isNotEmpty())
                .andExpect(jsonPath("$.calculationRule", org.hamcrest.Matchers.containsString("月末有效订阅")));

        // 终态不可逆：重复批准与事后拒绝都被状态守卫拒绝。
        mvc.perform(post("/api/v1/action-proposals/{id}/approval", proposalId)
                        .header("X-Workspace-Id", "workspace-demo")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", org.hamcrest.Matchers.containsString("不可审批")));
        mvc.perform(post("/api/v1/action-proposals/{id}/rejection", proposalId)
                        .header("X-Workspace-Id", "workspace-demo")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void strictPolicyBlocksSelfApprovalAndAllowsAnotherApprover() throws Exception {
        // 将 demo 策略升级为 v2 并要求分离审批；提案在 v2 之后创建才不过期。
        updatePolicyAsOwner("""
                update workspace_policy
                set policy_version = 2, requires_separate_approver = true
                where workspace_id = 'workspace-demo'
                """);
        grantApprovePermission(CAROL);

        String conversationId = createConversation("分离审批");
        String runId = prepareCustomCaliberDrilldownRun(conversationId, "只统计月末有效订阅");
        String proposalId = submitProposal(runId);
        postConfirm(proposalId);

        // 发起者自批被拒。
        mvc.perform(post("/api/v1/action-proposals/{id}/approval", proposalId)
                        .header("X-Workspace-Id", "workspace-demo")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", org.hamcrest.Matchers.containsString("分离审批")));

        // 队列对 CAROL 可见；另一名有权限成员批准成功。
        mvc.perform(get("/api/v1/action-proposals")
                        .header("X-Workspace-Id", "workspace-demo")
                        .with(jwt().jwt(token -> token.subject(CAROL))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.actionProposalId == '%s')]", proposalId).exists());
        mvc.perform(post("/api/v1/action-proposals/{id}/approval", proposalId)
                        .header("X-Workspace-Id", "workspace-demo")
                        .with(jwt().jwt(token -> token.subject(CAROL))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decidedBy").value(CAROL));
    }

    @Test
    void stalePolicyVersionProposalsCannotBeApproved() throws Exception {
        String conversationId = createConversation("策略过期");
        String runId = prepareCustomCaliberDrilldownRun(conversationId, "只统计月末有效订阅");
        String proposalId = submitProposal(runId);
        postConfirm(proposalId);

        // 提案基于 v1 创建；策略升级到 v2 后提案过期。
        updatePolicyAsOwner("""
                update workspace_policy set policy_version = policy_version + 1
                where workspace_id = 'workspace-demo'
                """);
        mvc.perform(post("/api/v1/action-proposals/{id}/approval", proposalId)
                        .header("X-Workspace-Id", "workspace-demo")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", org.hamcrest.Matchers.containsString("过期")));
    }

    @Test
    void memberCreatesRevisionProposalsDirectlyWithIdempotentReplay() throws Exception {
        String first = createRevision("v4", "累计订阅事件，排除一次性费用");
        mvc.perform(get("/api/v1/action-proposals")
                        .header("X-Workspace-Id", "workspace-demo")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.actionProposalId == '%s')].status", first).value("AWAITING_APPROVAL"));

        // 相同参数重放首提案。
        assertThat(createRevision("v4", "累计订阅事件，排除一次性费用")).isEqualTo(first);

        // 参数变化即新提案，旧提案被取代且不可再审批。
        String second = createRevision("v4", "累计订阅事件，排除一次性费用与税费");
        assertThat(second).isNotEqualTo(first);
        mvc.perform(post("/api/v1/action-proposals/{id}/approval", first)
                        .header("X-Workspace-Id", "workspace-demo")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isBadRequest());

        // 宽松策略下治理成员可自批自己的修订提案（confirm-to-record）。
        mvc.perform(post("/api/v1/action-proposals/{id}/approval", second)
                        .header("X-Workspace-Id", "workspace-demo")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.metricKey").value("mrr"))
                .andExpect(jsonPath("$.versionLabel").value("v4"));
    }

    @Test
    void membersWithoutApprovalPermissionCannotDecide() throws Exception {
        String conversationId = createConversation("权限守卫");
        String runId = prepareCustomCaliberDrilldownRun(conversationId, "只统计月末有效订阅");
        String proposalId = submitProposal(runId);
        postConfirm(proposalId);

        // CAROL 没有审批权限：端点授权直接拒绝。
        mvc.perform(post("/api/v1/action-proposals/{id}/approval", proposalId)
                        .header("X-Workspace-Id", "workspace-demo")
                        .with(jwt().jwt(token -> token.subject(CAROL))))
                .andExpect(status().isForbidden());
    }


    @Test
    void governanceMemberRejectsAProposalIntoIrreversibleTerminalState() throws Exception {
        String conversationId = createConversation("拒绝路径");
        String runId = prepareCustomCaliberDrilldownRun(conversationId, "只统计月末有效订阅");
        String proposalId = submitProposal(runId);
        postConfirm(proposalId);

        mvc.perform(post("/api/v1/action-proposals/{id}/rejection", proposalId)
                        .header("X-Workspace-Id", "workspace-demo")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.decidedBy").value(ALICE))
                .andExpect(jsonPath("$.decidedAt").isNotEmpty())
                .andExpect(jsonPath("$.calculationRule", org.hamcrest.Matchers.containsString("月末有效订阅")));

        // 拒绝后不可再批准，也不会被重新审批。
        mvc.perform(post("/api/v1/action-proposals/{id}/approval", proposalId)
                        .header("X-Workspace-Id", "workspace-demo")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isBadRequest());
    }

    private String createRevision(String versionLabel, String rule) throws Exception {
        String body = """
                {"metricKey":"mrr","versionLabel":"%s",
                 "calculationRule":"%s","timeBoundary":"自然月末","exclusions":"一次性费用"}
                """.formatted(versionLabel, rule);
        String response = mvc.perform(post("/api/v1/action-proposals")
                        .header("X-Workspace-Id", "workspace-demo")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("actionProposalId").asText();
    }


    private void updatePolicyAsOwner(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            connection.createStatement().execute(sql);
        }
    }

    private void grantApprovePermission(String userSubject) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            connection.createStatement().execute("""
                    update workspace_membership
                    set permissions = permissions || ARRAY['APPROVE_ACTION_PROPOSAL']
                    where workspace_id = 'workspace-demo' and user_subject = '%s'
                    """.formatted(userSubject));
        }
    }

    private void postConfirm(String proposalId) throws Exception {
        mvc.perform(post("/api/v1/action-proposals/{id}/confirmation", proposalId)
                        .header("X-Workspace-Id", "workspace-demo")
                        .with(jwt().jwt(token -> token.subject(ALICE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_APPROVAL"));
    }

    private String prepareCustomCaliberDrilldownRun(String conversationId, String customRule) throws Exception {
        postMessage(conversationId, "为什么本月 MRR 下降？");
        postMessage(conversationId, "使用自定义口径：" + customRule);
        return runIdOf(postMessage(conversationId, "继续下钻 MRR 降幅来源"));
    }

    /** 口径确认后再次提交新的自定义口径并下钻，落在同一分析任务上。 */
    private String prepareContinuedCustomCaliberDrilldownRun(String conversationId, String customRule)
            throws Exception {
        postMessage(conversationId, "使用自定义口径：" + customRule);
        return runIdOf(postMessage(conversationId, "继续下钻 MRR 降幅来源"));
    }

    private String submitProposal(String runId) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/agent-run-proposals")
                        .header("X-Query-Grant", grantFor(runId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runId\":\"%s\",\"actionType\":\"PROMOTE_CUSTOM_CALIBER\"}".formatted(runId)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("actionProposalId").asText();
    }

    private String grantFor(String runId) {
        try {
            return objectMapper.readTree(
                    jdbcTemplate.queryForObject(
                            "select payload from agent_run_outbox where run_id = ?", String.class, runId))
                    .get("queryGrant").asText();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private String runIdOf(String messageResponse) throws Exception {
        return objectMapper.readTree(messageResponse).at("/agentRun/runId").asText();
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
}

package dev.askmetric.server.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.askmetric.server.agent.AgentQueryGrantService;
import dev.askmetric.server.agent.AgentRunEventSource;
import dev.askmetric.server.agent.AgentRunEventType;
import dev.askmetric.server.agent.AgentRunIntentRoute;
import dev.askmetric.server.agent.AgentRunMapper;
import dev.askmetric.server.agent.AgentRunOutboxMapper;
import dev.askmetric.server.agent.AgentRunRequest;
import dev.askmetric.server.agent.AnalysisTaskCommand;
import dev.askmetric.server.agent.DeterministicIntentRouter;
import dev.askmetric.server.agent.IntentDecision;
import dev.askmetric.server.agent.PersistedAgentRun;
import dev.askmetric.server.analysis.AnalysisFindingService;
import dev.askmetric.server.analysis.AnalysisTask;
import dev.askmetric.server.analysis.AnalysisTaskEventType;
import dev.askmetric.server.analysis.AnalysisTaskMapper;
import dev.askmetric.server.analysis.AnalysisTaskStatus;
import dev.askmetric.server.catalog.MetricDefinitionService;
import dev.askmetric.server.catalog.MetricDefinitionVersion;
import dev.askmetric.server.evidence.EvidenceSnapshotService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConversationService {
    private static final int MAX_TITLE_LENGTH = 200;
    private static final int MAX_MESSAGE_LENGTH = 4000;
    private static final Pattern CUSTOM_METRIC_DEFINITION = Pattern.compile(
            "自定义口径\\s*(?:为|是|[:：])?\\s*(.+)", Pattern.DOTALL);

    private final ConversationMapper mapper;
    private final AgentRunMapper agentRunMapper;
    private final AnalysisTaskMapper analysisTaskMapper;
    private final MessageIdempotencyMapper idempotencyMapper;
    private final AgentRunOutboxMapper outboxMapper;
    private final DeterministicIntentRouter intentRouter;
    private final DeterministicChatReply deterministicChatReply;
    private final MetricDefinitionService metricDefinitionService;
    private final EvidenceSnapshotService evidenceSnapshotService;
    private final AnalysisFindingService analysisFindingService;
    private final AgentQueryGrantService queryGrantService;
    private final ObjectMapper objectMapper;
    private final String requestTopic;

    ConversationService(
            ConversationMapper mapper,
            AgentRunMapper agentRunMapper,
            AnalysisTaskMapper analysisTaskMapper,
            MessageIdempotencyMapper idempotencyMapper,
            AgentRunOutboxMapper outboxMapper,
            DeterministicIntentRouter intentRouter,
            DeterministicChatReply deterministicChatReply,
            MetricDefinitionService metricDefinitionService,
            EvidenceSnapshotService evidenceSnapshotService,
            AnalysisFindingService analysisFindingService,
            AgentQueryGrantService queryGrantService,
            ObjectMapper objectMapper,
            @Value("${askmetric.rocketmq.request-topic:askmetric-agent-run-request}") String requestTopic) {
        this.mapper = mapper;
        this.agentRunMapper = agentRunMapper;
        this.analysisTaskMapper = analysisTaskMapper;
        this.idempotencyMapper = idempotencyMapper;
        this.outboxMapper = outboxMapper;
        this.intentRouter = intentRouter;
        this.deterministicChatReply = deterministicChatReply;
        this.metricDefinitionService = metricDefinitionService;
        this.evidenceSnapshotService = evidenceSnapshotService;
        this.analysisFindingService = analysisFindingService;
        this.queryGrantService = queryGrantService;
        this.objectMapper = objectMapper;
        this.requestTopic = requestTopic;
    }

    @Transactional(readOnly = true)
    public List<ConversationSummary> list(String userSubject, String workspaceId) {
        return mapper.list(userSubject, workspaceId);
    }

    @Transactional
    public ConversationSnapshot create(String userSubject, String workspaceId, CreateConversationRequest request) {
        String title = requiredText(request == null ? null : request.getTitle(), "title", MAX_TITLE_LENGTH);
        String conversationId = "conversation_" + UUID.randomUUID();
        return mapper.create(userSubject, workspaceId, conversationId, title)
                .orElseThrow(() -> new AccessDeniedException("Workspace Membership not found"));
    }

    @Transactional(readOnly = true)
    public ConversationSnapshot snapshot(String userSubject, String workspaceId, String conversationId) {
        ConversationSnapshot snapshot = mapper.find(userSubject, workspaceId, conversationId)
                .orElseThrow(() -> new AccessDeniedException("Conversation not found in Workspace"));
        snapshot.setMessages(mapper.messages(userSubject, workspaceId, conversationId));
        List<PersistedAgentRun> agentRuns = agentRunMapper.runs(userSubject, workspaceId, conversationId);
        agentRuns.forEach(agentRun -> agentRun.setAuditEvents(
                agentRunMapper.auditEvents(userSubject, workspaceId, agentRun.getRunId())));
        snapshot.setAgentRuns(agentRuns);
        snapshot.setAnalysisTasks(analysisTaskMapper.tasks(userSubject, workspaceId, conversationId));
        snapshot.setEvidenceSnapshots(evidenceSnapshotService.list(userSubject, workspaceId, conversationId));
        snapshot.setAnalysisFindings(analysisFindingService.list(userSubject, workspaceId, conversationId));
        return snapshot;
    }

    @Transactional
    public MessageProcessed submitMessage(
            String userSubject,
            String workspaceId,
            String conversationId,
            CreateMessageRequest request,
            String requestedIdempotencyKey) {
        String content = requiredText(request == null ? null : request.getContent(), "content", MAX_MESSAGE_LENGTH);
        String idempotencyKey = normalizeIdempotencyKey(requestedIdempotencyKey);
        MessageIdempotencyRecord reservation = reserveIdempotency(
                userSubject, workspaceId, conversationId, idempotencyKey, content);
        if (reservation != null && reservation.getResponseJson() != null) {
            return replayResponse(reservation.getResponseJson());
        }
        IntentDecision intent = intentRouter.route(content);
        ConversationMessage userMessage = mapper.appendMessage(
                        userSubject,
                        workspaceId,
                        conversationId,
                        "message_" + UUID.randomUUID(),
                        "user",
                        userSubject,
                        content)
                .orElseThrow(() -> new AccessDeniedException("Conversation not found in Workspace"));
        Optional<AnalysisTask> activeTask = intent.getRoute() == AgentRunIntentRoute.ANALYSIS
                ? analysisTaskMapper.findContinuable(userSubject, workspaceId, conversationId)
                : Optional.empty();
        String runId = "run_" + UUID.randomUUID();
        if (agentRunMapper.createRun(
                        userSubject,
                        workspaceId,
                        conversationId,
                        runId,
                        userMessage.getMessageId(),
                        intent.getRoute(),
                        intent.getConfidence())
                != 1) {
            throw new AccessDeniedException("Conversation not found in Workspace");
        }
        MessageProcessed processed;
        if (intent.getRoute() == AgentRunIntentRoute.ANALYSIS) {
            processed = processAnalysisMessage(
                    userSubject,
                    workspaceId,
                    conversationId,
                    content,
                    userMessage,
                    runId,
                    activeTask,
                    intent.getAnalysisTaskCommand());
        } else {
            processed = completeChat(userSubject, workspaceId, conversationId, content, userMessage, runId);
        }
        if (processed.getAgentRun().getAnalysisTaskId() != null
                && !processed.getAgentRun().getAuditEvents().getLast().getEventType().isTerminal()) {
            enqueueAnalysisRun(
                    conversationId,
                    content,
                    processed.getAnalysisTask().getGoal(),
                    processed.getAgentRun());
        }
        completeIdempotency(reservation, processed);
        return processed;
    }

    /** 预留幂等键；重复请求在当前事务提交后可以安全读取首次响应。 */
    private MessageIdempotencyRecord reserveIdempotency(
            String userSubject,
            String workspaceId,
            String conversationId,
            String idempotencyKey,
            String content) {
        if (idempotencyKey == null) {
            return null;
        }
        String requestHash = hash(content);
        int inserted = idempotencyMapper.reserve(
                "idempotency_" + UUID.randomUUID(),
                workspaceId,
                conversationId,
                userSubject,
                idempotencyKey,
                requestHash);
        MessageIdempotencyRecord record = idempotencyMapper.find(
                        workspaceId, conversationId, userSubject, idempotencyKey)
                .orElseThrow(() -> new AccessDeniedException("Conversation not found in Workspace"));
        if (inserted == 0) {
            if (!record.getRequestHash().equals(requestHash)) {
                throw new IdempotencyConflictException("Idempotency-Key 已用于不同的消息内容");
            }
            if (record.getResponseJson() == null) {
                throw new IllegalStateException("相同 Idempotency-Key 的请求正在处理中");
            }
        }
        return record;
    }

    private MessageProcessed replayResponse(String responseJson) {
        try {
            return objectMapper.readValue(responseJson, MessageProcessed.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法读取幂等请求的首次响应", exception);
        }
    }

    private void completeIdempotency(MessageIdempotencyRecord reservation, MessageProcessed processed) {
        if (reservation == null) {
            return;
        }
        try {
            if (idempotencyMapper.complete(reservation.getIdempotencyId(), objectMapper.writeValueAsString(processed)) != 1) {
                throw new IllegalStateException("无法保存幂等请求响应");
            }
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化幂等请求响应", exception);
        }
    }

    private void enqueueAnalysisRun(String conversationId, String message, String taskGoal, PersistedAgentRun run) {
        String runId = run.getRunId();
        String metricDefinitionVersionId = run.getMetricDefinitionVersionId();
        // 口径已确认的运行才签发查询授权；未确认口径的运行没有任何受治理查询能力。
        String queryGrant = metricDefinitionVersionId == null
                ? null
                : queryGrantService.mint(runId, java.time.Instant.now());
        AgentRunRequest request = new AgentRunRequest(
                "agent_run_request_" + runId,
                1,
                AgentRunEventType.REQUESTED,
                1,
                java.time.Instant.now(),
                conversationId,
                runId,
                message,
                taskGoal,
                agentRunMapper.latestSequence(runId),
                metricDefinitionVersionId,
                queryGrant);
        try {
            String payload = objectMapper.writeValueAsString(request);
            if (outboxMapper.enqueue(
                            "outbox_" + runId,
                            request.getEventId(),
                            runId,
                            requestTopic,
                            payload)
                    != 1) {
                throw new IllegalStateException("Agent Run 请求已存在或无法写入 Outbox");
            }
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化 Agent Run 请求", exception);
        }
    }

    private static String normalizeIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String normalized = key.strip();
        if (normalized.length() > 200 || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Idempotency-Key 必须是 1-200 个可见字符");
        }
        return normalized;
    }

    private static String hash(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private MessageProcessed completeChat(
            String userSubject,
            String workspaceId,
            String conversationId,
            String content,
            ConversationMessage userMessage,
            String runId) {
        appendAuditEvent(userSubject, workspaceId, runId, 1, AgentRunEventType.ACCEPTED, "普通聊天 Agent 运行已接受");
        appendAuditEvent(userSubject, workspaceId, runId, 2, AgentRunEventType.PROGRESS, "正在生成确定性聊天回复");
        ConversationMessage assistantMessage = mapper.appendMessage(
                        userSubject,
                        workspaceId,
                        conversationId,
                        "message_" + UUID.randomUUID(),
                        "assistant",
                        null,
                        deterministicChatReply.replyTo(content))
                .orElseThrow(() -> new AccessDeniedException("Conversation not found in Workspace"));
        appendAuditEvent(userSubject, workspaceId, runId, 3, AgentRunEventType.COMPLETED, "普通聊天 Agent 运行已完成");
        return new MessageProcessed(
                userMessage,
                assistantMessage,
                persistedRun(userSubject, workspaceId, conversationId, runId),
                null);
    }

    private MessageProcessed processAnalysisMessage(
            String userSubject,
            String workspaceId,
            String conversationId,
            String goal,
            ConversationMessage userMessage,
            String runId,
            Optional<AnalysisTask> activeTask,
            AnalysisTaskCommand taskCommand) {
        if (activeTask.isEmpty()) {
            if (taskCommand != AnalysisTaskCommand.NONE) {
                return requestTaskCommandClarification(
                        userSubject, workspaceId, conversationId, userMessage, runId);
            }
            return createAnalysisTask(userSubject, workspaceId, conversationId, goal, userMessage, runId);
        }
        AnalysisTask task = activeTask.orElseThrow();
        if (taskCommand == AnalysisTaskCommand.CONTINUE) {
            return continueAnalysisTask(userSubject, workspaceId, conversationId, userMessage, runId, task);
        }
        if (taskCommand == AnalysisTaskCommand.SWITCH) {
            return switchAnalysisTask(userSubject, workspaceId, conversationId, goal, userMessage, runId, task);
        }
        appendAuditEvent(userSubject, workspaceId, runId, 1, AgentRunEventType.ACCEPTED, "分析 Agent 运行已接受");
        appendAuditEvent(userSubject, workspaceId, runId, 2, AgentRunEventType.PROGRESS,
                "检测到当前对话已有分析任务，等待用户澄清");
        ConversationMessage assistantMessage = mapper.appendMessage(
                        userSubject,
                        workspaceId,
                        conversationId,
                        "message_" + UUID.randomUUID(),
                        "assistant",
                        null,
                        "当前对话已有分析任务。请说明要继续当前目标，还是切换到新的分析目标。")
                .orElseThrow(() -> new AccessDeniedException("Conversation not found in Workspace"));
        appendAuditEvent(userSubject, workspaceId, runId, 3, AgentRunEventType.COMPLETED, "已请求用户澄清分析目标");
        return new MessageProcessed(
                userMessage,
                assistantMessage,
                persistedRun(userSubject, workspaceId, conversationId, runId),
                null);
    }

    private MessageProcessed requestTaskCommandClarification(
            String userSubject,
            String workspaceId,
            String conversationId,
            ConversationMessage userMessage,
            String runId) {
        appendAuditEvent(userSubject, workspaceId, runId, 1, AgentRunEventType.ACCEPTED, "分析 Agent 运行已接受");
        appendAuditEvent(userSubject, workspaceId, runId, 2, AgentRunEventType.PROGRESS,
                "未找到活动分析任务，等待用户澄清");
        ConversationMessage assistantMessage = mapper.appendMessage(
                        userSubject,
                        workspaceId,
                        conversationId,
                        "message_" + UUID.randomUUID(),
                        "assistant",
                null,
                "当前对话没有可继续的分析任务。请先描述要调查的分析目标。")
                .orElseThrow(() -> new AccessDeniedException("Conversation not found in Workspace"));
        appendAuditEvent(userSubject, workspaceId, runId, 3, AgentRunEventType.COMPLETED, "已请求用户说明分析目标");
        return new MessageProcessed(
                userMessage,
                assistantMessage,
                persistedRun(userSubject, workspaceId, conversationId, runId),
                null);
    }

    private MessageProcessed continueAnalysisTask(
            String userSubject,
            String workspaceId,
            String conversationId,
            ConversationMessage userMessage,
            String runId,
            AnalysisTask activeTask) {
        appendAuditEvent(userSubject, workspaceId, runId, 1, AgentRunEventType.ACCEPTED, "分析 Agent 运行已接受");
        if (agentRunMapper.linkAnalysisTask(userSubject, workspaceId, runId, activeTask.getAnalysisTaskId()) != 1) {
            throw new AccessDeniedException("Analysis Task not found in Workspace");
        }
        Optional<MetricDefinitionVersion> standardDefinition = mrrDefinition(
                userSubject, workspaceId, activeTask.getGoal());
        if (standardDefinition.isPresent()
                && (activeTask.getMetricDefinitionVersionId() == null || explicitlySelectsMetric(userMessage.getContent()))) {
            return selectMrrDefinition(
                    userSubject,
                    workspaceId,
                    conversationId,
                    userMessage,
                    runId,
                    activeTask,
                    standardDefinition.orElseThrow());
        }
        if (activeTask.getMetricDefinitionVersionId() != null
                && agentRunMapper.bindMetricDefinition(
                                userSubject,
                                workspaceId,
                                runId,
                                activeTask.getAnalysisTaskId(),
                                activeTask.getMetricDefinitionVersionId())
                        != 1) {
            throw new AccessDeniedException("Metric Definition not found in Workspace");
        }
        resumeAnalysisTask(userSubject, workspaceId, conversationId, activeTask);
        appendAuditEvent(userSubject, workspaceId, runId, 2, AgentRunEventType.PROGRESS, "正在继续当前分析任务");
        return new MessageProcessed(
                userMessage,
                null,
                persistedRun(userSubject, workspaceId, conversationId, runId),
                activeTask);
    }

    private MessageProcessed selectMrrDefinition(
            String userSubject,
            String workspaceId,
            String conversationId,
            ConversationMessage userMessage,
            String runId,
            AnalysisTask activeTask,
            MetricDefinitionVersion standardDefinition) {
        String content = userMessage.getContent();
        Optional<String> customRule = customMetricRule(content);
        MetricDefinitionVersion selected;
        if (customRule.isPresent()) {
            selected = metricDefinitionService.createCustom(
                    userSubject, workspaceId, standardDefinition, customRule.orElseThrow());
        } else if (confirmsStandardMetric(content)) {
            selected = standardDefinition;
        } else {
            ConversationMessage assistantMessage = mapper.appendMessage(
                            userSubject,
                            workspaceId,
                            conversationId,
                            "message_" + UUID.randomUUID(),
                            "assistant",
                            null,
                            confirmationPrompt(standardDefinition))
                    .orElseThrow(() -> new AccessDeniedException("Conversation not found in Workspace"));
            holdTaskForCaliberConfirmation(
                    userSubject, workspaceId, conversationId, runId, activeTask,
                    2,
                    "阶段 clarification：已展示指标定义，等待业务用户确认口径",
                    "尚未确认指标口径，继续等待业务用户输入");
            return new MessageProcessed(
                    userMessage,
                    assistantMessage,
                    persistedRun(userSubject, workspaceId, conversationId, runId),
                    activeTask);
        }
        bindMetricDefinition(userSubject, workspaceId, runId, activeTask, selected);
        resumeAnalysisTask(userSubject, workspaceId, conversationId, activeTask);
        appendAuditEvent(userSubject, workspaceId, runId, 2, AgentRunEventType.PROGRESS,
                "已采用指标定义 " + selected.getVersionLabel());
        return new MessageProcessed(
                userMessage,
                null,
                persistedRun(userSubject, workspaceId, conversationId, runId),
                activeTask);
    }

    /** 口径未确认：记录 clarification 阶段事件并让任务等待输入，未确认前不执行受治理查询。 */
    private void holdTaskForCaliberConfirmation(
            String userSubject,
            String workspaceId,
            String conversationId,
            String runId,
            AnalysisTask task,
            long clarificationSequence,
            String clarificationMessage,
            String completedMessage) {
        appendAuditEvent(userSubject, workspaceId, runId, clarificationSequence,
                AgentRunEventType.CLARIFICATION, clarificationMessage);
        appendAuditEvent(userSubject, workspaceId, runId, clarificationSequence + 1,
                AgentRunEventType.COMPLETED, completedMessage);
        if (analysisTaskMapper.waitForInput(
                        userSubject, workspaceId, conversationId, task.getAnalysisTaskId())
                != 1) {
            throw new AccessDeniedException("Analysis Task not found in Workspace");
        }
        task.setStatus(AnalysisTaskStatus.WAITING_FOR_INPUT);
    }

    private void resumeAnalysisTask(
            String userSubject, String workspaceId, String conversationId, AnalysisTask task) {
        if (analysisTaskMapper.resume(userSubject, workspaceId, conversationId, task.getAnalysisTaskId()) != 1) {
            throw new AccessDeniedException("Analysis Task not found in Workspace");
        }
        task.setStatus(AnalysisTaskStatus.ACTIVE);
    }

    private void bindMetricDefinition(
            String userSubject,
            String workspaceId,
            String runId,
            AnalysisTask activeTask,
            MetricDefinitionVersion selected) {
        String taskId = activeTask.getAnalysisTaskId();
        String versionId = selected.getMetricDefinitionVersionId();
        if (analysisTaskMapper.bindMetricDefinition(userSubject, workspaceId, taskId, versionId) != 1) {
            throw new AccessDeniedException("Metric Definition not found in Workspace");
        }
        if (agentRunMapper.bindMetricDefinition(userSubject, workspaceId, runId, taskId, versionId) != 1) {
            throw new AccessDeniedException("Metric Definition not found in Workspace");
        }
        activeTask.setMetricDefinitionVersionId(versionId);
    }

    private static boolean confirmsStandardMetric(String content) {
        return content.contains("标准") && content.contains("口径");
    }

    private static boolean explicitlySelectsMetric(String content) {
        return confirmsStandardMetric(content) || customMetricRule(content).isPresent();
    }

    private static Optional<String> customMetricRule(String content) {
        Matcher matcher = CUSTOM_METRIC_DEFINITION.matcher(content);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String rule = matcher.group(1).strip();
        return rule.isEmpty() ? Optional.empty() : Optional.of(rule);
    }

    private MessageProcessed switchAnalysisTask(
            String userSubject,
            String workspaceId,
            String conversationId,
            String goal,
            ConversationMessage userMessage,
            String runId,
            AnalysisTask activeTask) {
        if (analysisTaskMapper.waitForInput(
                        userSubject,
                        workspaceId,
                        conversationId,
                        activeTask.getAnalysisTaskId())
                != 1) {
            throw new AccessDeniedException("Active Analysis Task not found in Workspace");
        }
        return createAnalysisTask(
                userSubject,
                workspaceId,
                conversationId,
                goal,
                userMessage,
                runId,
                activeTask.getAnalysisTaskId());
    }

    private MessageProcessed createAnalysisTask(
            String userSubject,
            String workspaceId,
            String conversationId,
            String goal,
            ConversationMessage userMessage,
            String runId) {
        return createAnalysisTask(userSubject, workspaceId, conversationId, goal, userMessage, runId, null);
    }

    private MessageProcessed createAnalysisTask(
            String userSubject,
            String workspaceId,
            String conversationId,
            String goal,
            ConversationMessage userMessage,
            String runId,
            String previousAnalysisTaskId) {
        appendAuditEvent(userSubject, workspaceId, runId, 1, AgentRunEventType.ACCEPTED, "分析 Agent 运行已接受");
        String analysisTaskId = "analysis_task_" + UUID.randomUUID();
        if (analysisTaskMapper.create(
                        userSubject,
                        workspaceId,
                        conversationId,
                        analysisTaskId,
                        goal,
                        AnalysisTaskStatus.ACTIVE,
                        runId)
                != 1) {
            throw new AccessDeniedException("Conversation not found in Workspace");
        }
        if (agentRunMapper.linkAnalysisTask(userSubject, workspaceId, runId, analysisTaskId) != 1) {
            throw new AccessDeniedException("Analysis Task not found in Workspace");
        }
        appendAuditEvent(userSubject, workspaceId, runId, 2, AgentRunEventType.PROGRESS, "分析任务已创建");
        long nextSequence = 3;
        if (previousAnalysisTaskId != null) {
            recordTaskSwitch(userSubject, workspaceId, conversationId, runId, previousAnalysisTaskId, analysisTaskId);
            appendAuditEvent(userSubject, workspaceId, runId, nextSequence++,
                    AgentRunEventType.PROGRESS, "已切换到新的分析任务");
        }
        Optional<MetricDefinitionVersion> standardDefinition = mrrDefinition(
                userSubject, workspaceId, goal);
        if (standardDefinition.isPresent()) {
            ConversationMessage assistantMessage = mapper.appendMessage(
                            userSubject,
                            workspaceId,
                            conversationId,
                            "message_" + UUID.randomUUID(),
                            "assistant",
                            null,
                            confirmationPrompt(standardDefinition.orElseThrow()))
                    .orElseThrow(() -> new AccessDeniedException("Conversation not found in Workspace"));
            AnalysisTask analysisTask = persistedAnalysisTask(
                    userSubject, workspaceId, conversationId, analysisTaskId);
            holdTaskForCaliberConfirmation(
                    userSubject, workspaceId, conversationId, runId, analysisTask,
                    nextSequence,
                    "阶段 clarification：已展示标准指标定义，等待业务用户确认口径",
                    "口径未确认，任务进入 waiting_for_input，暂不执行受治理查询");
            return new MessageProcessed(
                    userMessage,
                    assistantMessage,
                    persistedRun(userSubject, workspaceId, conversationId, runId),
                    analysisTask);
        }
        AnalysisTask analysisTask = persistedAnalysisTask(userSubject, workspaceId, conversationId, analysisTaskId);
        return new MessageProcessed(
                userMessage,
                null,
                persistedRun(userSubject, workspaceId, conversationId, runId),
                analysisTask);
    }

    private AnalysisTask persistedAnalysisTask(
            String userSubject, String workspaceId, String conversationId, String analysisTaskId) {
        return analysisTaskMapper.tasks(userSubject, workspaceId, conversationId).stream()
                .filter(candidate -> analysisTaskId.equals(candidate.getAnalysisTaskId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Persisted Analysis Task was not found"));
    }

    private Optional<MetricDefinitionVersion> mrrDefinition(
            String userSubject, String workspaceId, String goal) {
        if (!goal.toLowerCase(java.util.Locale.ROOT).contains("mrr")
                && !goal.contains("月度经常性收入")) {
            return Optional.empty();
        }
        return metricDefinitionService.latestStandard(userSubject, workspaceId, "mrr");
    }

    private static String confirmationPrompt(MetricDefinitionVersion definition) {
        return ("开始查询前，请确认是否使用语义目录中的 %s %s（%s）：计算规则：%s；时间边界：%s；排除项：%s。"
                        + "回复“使用标准 %s %s 口径”，或“使用自定义口径：你的完整规则”。")
                .formatted(
                        definition.getMetricKey().toUpperCase(java.util.Locale.ROOT),
                        definition.getVersionLabel(),
                        definition.getDisplayName(),
                        definition.getCalculationRule(),
                        definition.getTimeBoundary(),
                        definition.getExclusions(),
                        definition.getMetricKey().toUpperCase(java.util.Locale.ROOT),
                        definition.getVersionLabel());
    }

    private void recordTaskSwitch(
            String userSubject,
            String workspaceId,
            String conversationId,
            String sourceAgentRunId,
            String previousAnalysisTaskId,
            String currentAnalysisTaskId) {
        if (analysisTaskMapper.appendSwitchEvent(
                        userSubject,
                        workspaceId,
                        conversationId,
                        "analysis_task_event_" + UUID.randomUUID(),
                        sourceAgentRunId,
                        previousAnalysisTaskId,
                        currentAnalysisTaskId,
                        AnalysisTaskEventType.SWITCHED)
                != 1) {
            throw new AccessDeniedException("Analysis Task switch could not be recorded in Workspace");
        }
    }

    private PersistedAgentRun persistedRun(
            String userSubject, String workspaceId, String conversationId, String runId) {
        PersistedAgentRun agentRun = agentRunMapper.runs(userSubject, workspaceId, conversationId).stream()
                .filter(candidate -> runId.equals(candidate.getRunId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Persisted Agent Run was not found"));
        agentRun.setAuditEvents(agentRunMapper.auditEvents(userSubject, workspaceId, runId));
        return agentRun;
    }

    private void appendAuditEvent(
            String userSubject,
            String workspaceId,
            String runId,
            long sequence,
            AgentRunEventType eventType,
            String message) {
        if (agentRunMapper.appendAuditEvent(
                        userSubject,
                        workspaceId,
                        "event_" + UUID.randomUUID(),
                        runId,
                        sequence,
                        eventType,
                        message,
                        AgentRunEventSource.JAVA)
                != 1) {
            throw new AccessDeniedException("Agent Run not found in Workspace");
        }
    }

    private static String requiredText(String value, String field, int maximumLength) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        String normalized = value.trim();
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + " must not exceed " + maximumLength + " characters");
        }
        return normalized;
    }
}

package dev.askmetric.server.conversation;

import dev.askmetric.server.agent.AgentRunEventSource;
import dev.askmetric.server.agent.AgentRunEventType;
import dev.askmetric.server.agent.AgentRunIntentRoute;
import dev.askmetric.server.agent.AgentRunMapper;
import dev.askmetric.server.agent.AnalysisTaskCommand;
import dev.askmetric.server.agent.DeterministicIntentRouter;
import dev.askmetric.server.agent.IntentDecision;
import dev.askmetric.server.agent.PersistedAgentRun;
import dev.askmetric.server.analysis.AnalysisTask;
import dev.askmetric.server.analysis.AnalysisTaskEventType;
import dev.askmetric.server.analysis.AnalysisTaskMapper;
import dev.askmetric.server.analysis.AnalysisTaskStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConversationService {
    private static final int MAX_TITLE_LENGTH = 200;
    private static final int MAX_MESSAGE_LENGTH = 4000;

    private final ConversationMapper mapper;
    private final AgentRunMapper agentRunMapper;
    private final AnalysisTaskMapper analysisTaskMapper;
    private final DeterministicIntentRouter intentRouter;
    private final DeterministicChatReply deterministicChatReply;

    ConversationService(
            ConversationMapper mapper,
            AgentRunMapper agentRunMapper,
            AnalysisTaskMapper analysisTaskMapper,
            DeterministicIntentRouter intentRouter,
            DeterministicChatReply deterministicChatReply) {
        this.mapper = mapper;
        this.agentRunMapper = agentRunMapper;
        this.analysisTaskMapper = analysisTaskMapper;
        this.intentRouter = intentRouter;
        this.deterministicChatReply = deterministicChatReply;
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
        return snapshot;
    }

    @Transactional
    public MessageProcessed submitMessage(
            String userSubject,
            String workspaceId,
            String conversationId,
            CreateMessageRequest request) {
        String content = requiredText(request == null ? null : request.getContent(), "content", MAX_MESSAGE_LENGTH);
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
                ? analysisTaskMapper.findActive(userSubject, workspaceId, conversationId)
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
        if (intent.getRoute() == AgentRunIntentRoute.ANALYSIS) {
            return processAnalysisMessage(
                    userSubject,
                    workspaceId,
                    conversationId,
                    content,
                    userMessage,
                    runId,
                    activeTask,
                    intent.getAnalysisTaskCommand());
        }
        return completeChat(userSubject, workspaceId, conversationId, content, userMessage, runId);
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
                "检测到当前对话已有活动分析任务，等待用户澄清");
        ConversationMessage assistantMessage = mapper.appendMessage(
                        userSubject,
                        workspaceId,
                        conversationId,
                        "message_" + UUID.randomUUID(),
                        "assistant",
                        null,
                        "当前对话已有活动分析任务。请说明要继续当前目标，还是切换到新的分析目标。")
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
                        "当前对话没有活动分析任务。请先描述要调查的分析目标。")
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
        appendAuditEvent(userSubject, workspaceId, runId, 2, AgentRunEventType.PROGRESS, "正在继续当前分析任务");
        return new MessageProcessed(
                userMessage,
                null,
                persistedRun(userSubject, workspaceId, conversationId, runId),
                null);
    }

    private MessageProcessed switchAnalysisTask(
            String userSubject,
            String workspaceId,
            String conversationId,
            String goal,
            ConversationMessage userMessage,
            String runId,
            AnalysisTask activeTask) {
        if (analysisTaskMapper.changeStatus(
                        userSubject,
                        workspaceId,
                        conversationId,
                        activeTask.getAnalysisTaskId(),
                        AnalysisTaskStatus.WAITING_FOR_INPUT)
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
        if (previousAnalysisTaskId != null) {
            recordTaskSwitch(userSubject, workspaceId, conversationId, runId, previousAnalysisTaskId, analysisTaskId);
            appendAuditEvent(userSubject, workspaceId, runId, 3, AgentRunEventType.PROGRESS, "已切换到新的分析任务");
        }
        AnalysisTask analysisTask = analysisTaskMapper.tasks(userSubject, workspaceId, conversationId).stream()
                .filter(candidate -> analysisTaskId.equals(candidate.getAnalysisTaskId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Persisted Analysis Task was not found"));
        return new MessageProcessed(
                userMessage,
                null,
                persistedRun(userSubject, workspaceId, conversationId, runId),
                analysisTask);
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

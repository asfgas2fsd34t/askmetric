package dev.askmetric.server.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.askmetric.server.agent.AgentRunEventSource;
import dev.askmetric.server.agent.AgentRunEventType;
import dev.askmetric.server.agent.AgentRunIntentRoute;
import dev.askmetric.server.agent.AgentRunMapper;
import dev.askmetric.server.agent.AgentRunOutboxMapper;
import dev.askmetric.server.agent.DeterministicIntentRouter;
import dev.askmetric.server.agent.PersistedAgentRun;
import dev.askmetric.server.analysis.AnalysisTask;
import dev.askmetric.server.analysis.AnalysisTaskMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ConversationServiceTest {
    private static final String USER = "user-1";
    private static final String WORKSPACE = "workspace-1";
    private static final String CONVERSATION = "conversation-1";

    @Test
    void enqueuesAContinuationForTheActiveAnalysisTask() {
        ConversationMapper conversationMapper = mock(ConversationMapper.class);
        AgentRunMapper agentRunMapper = mock(AgentRunMapper.class);
        AnalysisTaskMapper analysisTaskMapper = mock(AnalysisTaskMapper.class);
        MessageIdempotencyMapper idempotencyMapper = mock(MessageIdempotencyMapper.class);
        AgentRunOutboxMapper outboxMapper = mock(AgentRunOutboxMapper.class);
        AnalysisTask activeTask = analysisTask("analysis-task-1");
        ConversationMessage userMessage = message("message-1", "继续");
        PersistedAgentRun persistedRun = persistedRun("run-1", activeTask.getAnalysisTaskId());

        when(analysisTaskMapper.findActive(USER, WORKSPACE, CONVERSATION)).thenReturn(Optional.of(activeTask));
        when(conversationMapper.appendMessage(
                eq(USER), eq(WORKSPACE), eq(CONVERSATION), anyString(), eq("user"), eq(USER), eq("继续")))
                .thenReturn(Optional.of(userMessage));
        when(agentRunMapper.createRun(
                eq(USER), eq(WORKSPACE), eq(CONVERSATION), anyString(), eq("message-1"),
                eq(AgentRunIntentRoute.ANALYSIS), any(BigDecimal.class)))
                .thenAnswer(invocation -> {
                    persistedRun.setRunId(invocation.getArgument(3));
                    return 1;
                });
        when(agentRunMapper.linkAnalysisTask(eq(USER), eq(WORKSPACE), anyString(), eq("analysis-task-1")))
                .thenReturn(1);
        when(agentRunMapper.appendAuditEvent(
                eq(USER), eq(WORKSPACE), anyString(), anyString(), anyLong(),
                any(AgentRunEventType.class), anyString(), eq(AgentRunEventSource.JAVA)))
                .thenReturn(1);
        when(agentRunMapper.runs(USER, WORKSPACE, CONVERSATION)).thenAnswer(invocation -> List.of(persistedRun));
        when(agentRunMapper.auditEvents(eq(USER), eq(WORKSPACE), anyString())).thenReturn(List.of());
        when(outboxMapper.enqueue(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(1);

        ConversationService service = new ConversationService(
                conversationMapper,
                agentRunMapper,
                analysisTaskMapper,
                idempotencyMapper,
                outboxMapper,
                new DeterministicIntentRouter(),
                new DeterministicChatReply(),
                new ObjectMapper().findAndRegisterModules(),
                "askmetric-agent-run-request");

        CreateMessageRequest request = new CreateMessageRequest();
        request.setContent("继续");
        MessageProcessed processed = service.submitMessage(USER, WORKSPACE, CONVERSATION, request, null);

        assertThat(processed.getAnalysisTask()).isSameAs(activeTask);
        verify(outboxMapper).enqueue(anyString(), anyString(), eq(persistedRun.getRunId()),
                eq("askmetric-agent-run-request"), anyString());
    }

    private static AnalysisTask analysisTask(String taskId) {
        AnalysisTask task = new AnalysisTask();
        task.setAnalysisTaskId(taskId);
        task.setConversationId(CONVERSATION);
        return task;
    }

    private static ConversationMessage message(String messageId, String content) {
        ConversationMessage message = new ConversationMessage();
        message.setMessageId(messageId);
        message.setConversationId(CONVERSATION);
        message.setContent(content);
        return message;
    }

    private static PersistedAgentRun persistedRun(String runId, String taskId) {
        PersistedAgentRun run = new PersistedAgentRun();
        run.setRunId(runId);
        run.setConversationId(CONVERSATION);
        run.setAnalysisTaskId(taskId);
        run.setIntentRoute(AgentRunIntentRoute.ANALYSIS);
        run.setIntentConfidence(BigDecimal.ONE);
        run.setCreatedAt(Instant.parse("2026-09-02T00:00:00Z"));
        return run;
    }
}

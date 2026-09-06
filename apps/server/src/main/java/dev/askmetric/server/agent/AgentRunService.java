package dev.askmetric.server.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class AgentRunService {
    private final AgentRunSseHub sseHub;
    private final AgentRunMapper agentRunMapper;
    private final AgentRunOutboxMapper outboxMapper;
    private final ObjectMapper objectMapper;
    private final String requestTopic;

    public AgentRunService(
            AgentRunSseHub sseHub,
            AgentRunMapper agentRunMapper,
            AgentRunOutboxMapper outboxMapper,
            ObjectMapper objectMapper,
            @Value("${askmetric.rocketmq.request-topic:askmetric-agent-run-request}") String requestTopic) {
        this.sseHub = sseHub;
        this.agentRunMapper = agentRunMapper;
        this.outboxMapper = outboxMapper;
        this.objectMapper = objectMapper;
        this.requestTopic = requestTopic;
    }

    @Transactional
    public void acceptEvent(AgentRunEvent event) {
        int inserted = agentRunMapper.appendExternalEvent(
                event.getEventId(),
                event.getRunId(),
                event.getSequence(),
                event.getEventType(),
                event.getOccurredAt(),
                event.getConversationId(),
                event.getMessage(),
                event.getSource());
        if (inserted == 1) {
            publishAfterCommit(event.getConversationId(), event.getRunId());
            return;
        }
        if (inserted == 0 && agentRunMapper.isTerminal(event.getRunId())) {
            publishAfterCommit(event.getConversationId(), event.getRunId());
            return;
        }
        if (inserted == 0
                && (agentRunMapper.eventExists(event.getEventId(), event.getRunId())
                        || agentRunMapper.sequenceExists(event.getRunId(), event.getSequence()))) {
            publishAfterCommit(event.getConversationId(), event.getRunId());
            return;
        }
        throw new IllegalArgumentException("Agent Run event was not accepted: " + event.getEventId());
    }

    public boolean exists(String workspaceId, String conversationId, String runId) {
        return agentRunMapper.exists(workspaceId, conversationId, runId);
    }

    /** 取消运行和 Analysis Task，并通过 Outbox 通知 Python 停止后续执行。 */
    @Transactional
    public Optional<AgentRunEvent> cancel(
            String userSubject,
            String workspaceId,
            String conversationId,
            String runId,
            String reason) {
        String eventId = "agent_run_cancelled_" + runId;
        Optional<AgentRunEvent> cancelled = agentRunMapper.cancelRun(
                userSubject, workspaceId, conversationId, runId, eventId, reason);
        if (cancelled.isEmpty()) {
            return Optional.empty();
        }
        AgentRunEvent event = cancelled.orElseThrow();
        AgentRunRequest command = new AgentRunRequest(
                "agent_run_cancel_request_" + runId,
                1,
                AgentRunEventType.CANCEL_REQUESTED,
                event.getSequence(),
                event.getOccurredAt(),
                conversationId,
                runId,
                event.getMessage());
        try {
            if (outboxMapper.enqueue(
                            "outbox_cancel_" + runId,
                            command.getEventId(),
                            runId,
                            requestTopic,
                            objectMapper.writeValueAsString(command))
                    != 1) {
                throw new IllegalStateException("Agent Run 取消请求已存在或无法写入 Outbox");
            }
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化 Agent Run 取消请求", exception);
        }
        publishAfterCommit(conversationId, runId);
        return cancelled;
    }

    public void addReplay(String conversationId, String runId, long afterSequence, SseEmitter emitter) {
        sseHub.registerAndReplay(
                runId,
                afterSequence,
                emitter,
                () -> agentRunMapper.eventsForRun(conversationId, runId));
    }

    private void publishAfterCommit(String conversationId, String runId) {
        Runnable publish = () -> sseHub.publishPersisted(
                runId, () -> agentRunMapper.eventsForRun(conversationId, runId));
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publish.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publish.run();
            }
        });
    }

}

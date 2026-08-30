package dev.askmetric.server.agent;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class AgentRunService {
    private final AgentRunStore store;
    private final RocketMqGateway gateway;

    public AgentRunService(AgentRunStore store, RocketMqGateway gateway) {
        this.store = store;
        this.gateway = gateway;
    }

    public AgentRunAccepted submit(String workspaceId, String conversationId, AgentRunSubmission submission) {
        String runId = "run_" + UUID.randomUUID();
        AgentRunRequest request = new AgentRunRequest(
                UUID.randomUUID().toString(),
                1,
                AgentRunEventType.REQUESTED,
                1,
                Instant.now(),
                conversationId,
                runId,
                submission.getMessage().trim());
        store.create(runId, workspaceId, conversationId);
        // 先记录 QUEUED，确保消息队列投递失败时仍有可审计的 Agent Run 与连续事件序号。
        store.append(new AgentRunEvent(
                request.getEventId(),
                1,
                AgentRunEventType.ACCEPTED,
                1,
                request.getOccurredAt(),
                conversationId,
                runId,
                "Agent Run 已进入队列",
                AgentRunEventSource.JAVA));
        try {
            gateway.publish(request);
        } catch (RuntimeException exception) {
            // 投递失败不是丢弃运行：将其转为终态，让客户端和后续审计都能观察到失败原因。
            store.append(new AgentRunEvent(
                    UUID.randomUUID().toString(),
                    1,
                    AgentRunEventType.FAILED,
                    2,
                    Instant.now(),
                    conversationId,
                    runId,
                    "Agent Run 无法投递到消息队列",
                    AgentRunEventSource.JAVA));
            throw new AgentRunPublishException(
                    accepted(conversationId, runId), exception);
        }
        return accepted(conversationId, runId);
    }

    public void acceptEvent(AgentRunEvent event) {
        store.append(event);
    }

    public boolean exists(String workspaceId, String conversationId, String runId) {
        return store.exists(runId, workspaceId, conversationId);
    }

    public void addReplay(String runId, long afterSequence, SseEmitter emitter) {
        store.registerAndReplay(runId, afterSequence, emitter);
    }

    private static AgentRunAccepted accepted(String conversationId, String runId) {
        return new AgentRunAccepted(
                conversationId,
                runId,
                "/api/v1/conversations/%s/runs/%s/events".formatted(conversationId, runId));
    }
}

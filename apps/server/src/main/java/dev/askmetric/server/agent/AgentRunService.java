package dev.askmetric.server.agent;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class AgentRunService {
    private final AgentRunStore store;
    private final AgentRunMapper agentRunMapper;

    @org.springframework.beans.factory.annotation.Autowired
    public AgentRunService(AgentRunStore store, AgentRunMapper agentRunMapper) {
        this.store = store;
        this.agentRunMapper = agentRunMapper;
    }

    public void acceptEvent(AgentRunEvent event) {
        if (agentRunMapper == null) {
            store.append(event);
            return;
        }
        int inserted = agentRunMapper.appendExternalEvent(
                event.getEventId(),
                event.getRunId(),
                event.getSequence(),
                event.getEventType(),
                event.getOccurredAt(),
                event.getConversationId(),
                event.getMessage(),
                event.getSource());
        if (store.exists(event.getRunId())) {
            store.append(event);
            return;
        }
        if (inserted == 1) {
            agentRunMapper.workspaceId(event.getConversationId(), event.getRunId())
                    .ifPresent(workspaceId -> hydrate(event.getConversationId(), event.getRunId(), workspaceId));
            return;
        }
        if (inserted == 0
                && !agentRunMapper.eventExists(event.getEventId(), event.getRunId())
                && !agentRunMapper.sequenceExists(event.getRunId(), event.getSequence())) {
            throw new IllegalArgumentException("Agent Run event was not accepted: " + event.getEventId());
        }
    }

    public boolean exists(String workspaceId, String conversationId, String runId) {
        return store.exists(runId, workspaceId, conversationId)
                || agentRunMapper != null && agentRunMapper.exists(workspaceId, conversationId, runId);
    }

    public void addReplay(String workspaceId, String conversationId, String runId, long afterSequence, SseEmitter emitter) {
        if (!store.exists(runId) && agentRunMapper != null) {
            hydrate(conversationId, runId, workspaceId);
        }
        store.registerAndReplay(runId, afterSequence, emitter);
    }

    private void hydrate(String conversationId, String runId, String workspaceId) {
        if (agentRunMapper == null) {
            return;
        }
        var events = agentRunMapper.eventsForRun(conversationId, runId);
        if (!events.isEmpty()) {
            store.restore(runId, workspaceId, conversationId, events);
        }
    }

}

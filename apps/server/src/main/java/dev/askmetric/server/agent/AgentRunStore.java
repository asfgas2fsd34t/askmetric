package dev.askmetric.server.agent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class AgentRunStore {
    private final Map<String, CopyOnWriteArrayList<AgentRunEvent>> eventsByRun = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArraySet<SseEmitter>> emittersByRun = new ConcurrentHashMap<>();
    private final Map<String, String> conversationByRun = new ConcurrentHashMap<>();

    public void create(String runId, String conversationId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId is required");
        }
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId is required");
        }
        conversationByRun.putIfAbsent(runId, conversationId);
        eventsByRun.putIfAbsent(runId, new CopyOnWriteArrayList<>());
    }

    public synchronized boolean append(AgentRunEvent event) {
        // 校验、去重、状态迁移和向 SSE 订阅者广播必须处在同一临界区，
        // 否则并发重投可能越过序号检查，或让刚订阅的客户端漏掉事件。
        if (!eventsByRun.containsKey(event.runId())) {
            throw new IllegalArgumentException("Agent Run does not exist: " + event.runId());
        }
        String knownConversation = conversationByRun.putIfAbsent(event.runId(), event.conversationId());
        if (knownConversation != null && !knownConversation.equals(event.conversationId())) {
            throw new IllegalArgumentException("conversationId must match the Agent Run");
        }
        var events = eventsByRun.get(event.runId());
        if (events.stream().anyMatch(existing -> existing.eventId().equals(event.eventId()))) {
            // RocketMQ 是 at-least-once 投递；相同 eventId 的重投不能改变运行状态。
            return false;
        }
        if (!events.isEmpty() && events.getLast().eventType().isTerminal()) {
            return false;
        }
        if (!events.isEmpty() && !isValidTransition(events.getLast().eventType(), event.eventType())) {
            return false;
        }
        long expectedSequence = events.isEmpty() ? 1 : events.getLast().sequence() + 1;
        if (event.sequence() < expectedSequence) {
            // 旧事件可能在重试后晚到，保留已知的较新状态即可。
            return false;
        }
        if (event.sequence() > expectedSequence) {
            throw new IllegalArgumentException("event sequence must be " + expectedSequence + " for run " + event.runId());
        }
        events.add(event);
        var emitters = emittersByRun.getOrDefault(event.runId(), new CopyOnWriteArraySet<>());
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .id(Long.toString(event.sequence()))
                        .name(event.eventType().wireValue())
                        .data(event));
                if (event.eventType().isTerminal()) {
                    emitter.complete();
                    emitters.remove(emitter);
                }
            } catch (Exception exception) {
                emitters.remove(emitter);
                emitter.completeWithError(exception);
            }
        }
        return true;
    }

    public List<AgentRunEvent> replay(String runId, long afterSequence) {
        return eventsByRun.getOrDefault(runId, new CopyOnWriteArrayList<>()).stream()
                .filter(event -> event.sequence() > afterSequence)
                .toList();
    }

    public void register(String runId, SseEmitter emitter) {
        emittersByRun.computeIfAbsent(runId, ignored -> new CopyOnWriteArraySet<>()).add(emitter);
        emitter.onCompletion(() -> remove(runId, emitter));
        emitter.onTimeout(() -> remove(runId, emitter));
        emitter.onError(ignored -> remove(runId, emitter));
    }

    public synchronized void registerAndReplay(String runId, long afterSequence, SseEmitter emitter) {
        // 先注册再补放历史，避免 append 恰好发生在 replay 与 register 之间而造成事件丢失。
        register(runId, emitter);
        for (AgentRunEvent event : replay(runId, afterSequence)) {
            try {
                send(emitter, event);
                if (event.eventType().isTerminal()) {
                    emitter.complete();
                    remove(runId, emitter);
                    return;
                }
            } catch (Exception exception) {
                remove(runId, emitter);
                emitter.completeWithError(exception);
                return;
            }
        }
        var events = eventsByRun.get(runId);
        if (events != null && !events.isEmpty() && events.getLast().eventType().isTerminal()) {
            emitter.complete();
            remove(runId, emitter);
        }
    }

    private static void send(SseEmitter emitter, AgentRunEvent event) throws IOException {
        emitter.send(SseEmitter.event()
                .id(Long.toString(event.sequence()))
                .name(event.eventType().wireValue())
                .data(event));
    }

    private static boolean isValidTransition(AgentRunEventType current, AgentRunEventType next) {
        return switch (current) {
            case ACCEPTED -> next == AgentRunEventType.PROGRESS || next == AgentRunEventType.FAILED;
            case PROGRESS -> next == AgentRunEventType.PROGRESS
                    || next == AgentRunEventType.COMPLETED
                    || next == AgentRunEventType.FAILED;
            case COMPLETED, FAILED, REQUESTED -> false;
        };
    }

    private void remove(String runId, SseEmitter emitter) {
        var emitters = emittersByRun.get(runId);
        if (emitters != null) {
            emitters.remove(emitter);
        }
    }

    public boolean exists(String runId) {
        return eventsByRun.containsKey(runId);
    }

    public boolean exists(String runId, String conversationId) {
        return exists(runId) && conversationId != null && conversationId.equals(conversationByRun.get(runId));
    }

    public List<AgentRunEvent> snapshot(String runId) {
        return new ArrayList<>(eventsByRun.getOrDefault(runId, new CopyOnWriteArrayList<>()));
    }
}

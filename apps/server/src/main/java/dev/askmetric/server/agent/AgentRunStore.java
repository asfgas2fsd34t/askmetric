package dev.askmetric.server.agent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
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
    private final Map<String, String> workspaceByRun = new ConcurrentHashMap<>();

    public void create(String runId, String workspaceId, String conversationId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId is required");
        }
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId is required");
        }
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new IllegalArgumentException("workspaceId is required");
        }
        workspaceByRun.putIfAbsent(runId, workspaceId);
        conversationByRun.putIfAbsent(runId, conversationId);
        eventsByRun.putIfAbsent(runId, new CopyOnWriteArrayList<>());
    }

    /** 将数据库事件恢复为 SSE 投影，不向当前连接重复广播历史事件。 */
    public synchronized void restore(
            String runId, String workspaceId, String conversationId, List<AgentRunEvent> events) {
        create(runId, workspaceId, conversationId);
        var restored = eventsByRun.get(runId);
        var knownEventIds = new HashSet<String>();
        for (AgentRunEvent existing : restored) {
            knownEventIds.add(existing.getEventId());
        }
        for (AgentRunEvent event : events) {
            if (knownEventIds.add(event.getEventId())) {
                restored.add(event);
            }
        }
        restored.sort(java.util.Comparator.comparingLong(AgentRunEvent::getSequence));
    }

    public synchronized boolean append(AgentRunEvent event) {
        // 校验、去重、状态迁移和向 SSE 订阅者广播必须处在同一临界区，
        // 否则并发重投可能越过序号检查，或让刚订阅的客户端漏掉事件。
        if (!eventsByRun.containsKey(event.getRunId())) {
            throw new IllegalArgumentException("Agent Run does not exist: " + event.getRunId());
        }
        String knownConversation = conversationByRun.putIfAbsent(event.getRunId(), event.getConversationId());
        if (knownConversation != null && !knownConversation.equals(event.getConversationId())) {
            throw new IllegalArgumentException("conversationId must match the Agent Run");
        }
        var events = eventsByRun.get(event.getRunId());
        if (events.stream().anyMatch(existing -> existing.getEventId().equals(event.getEventId()))) {
            // RocketMQ 是 at-least-once 投递；相同 eventId 的重投不能改变运行状态。
            return false;
        }
        if (!events.isEmpty() && events.getLast().getEventType().isTerminal()) {
            return false;
        }
        if (!events.isEmpty() && !isValidTransition(events.getLast().getEventType(), event.getEventType())) {
            return false;
        }
        long expectedSequence = events.isEmpty() ? 1 : events.getLast().getSequence() + 1;
        if (event.getSequence() < expectedSequence) {
            // 旧事件可能在重试后晚到，保留已知的较新状态即可。
            return false;
        }
        if (event.getSequence() > expectedSequence) {
            throw new IllegalArgumentException("event sequence must be " + expectedSequence + " for run " + event.getRunId());
        }
        events.add(event);
        var emitters = emittersByRun.getOrDefault(event.getRunId(), new CopyOnWriteArraySet<>());
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .id(Long.toString(event.getSequence()))
                        .name(event.getEventType().wireValue())
                        .data(event));
                if (event.getEventType().isTerminal()) {
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
                .filter(event -> event.getSequence() > afterSequence)
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
                if (event.getEventType().isTerminal()) {
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
        if (events != null && !events.isEmpty() && events.getLast().getEventType().isTerminal()) {
            emitter.complete();
            remove(runId, emitter);
        }
    }

    private static void send(SseEmitter emitter, AgentRunEvent event) throws IOException {
        emitter.send(SseEmitter.event()
                .id(Long.toString(event.getSequence()))
                .name(event.getEventType().wireValue())
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

    public boolean exists(String runId, String workspaceId, String conversationId) {
        return exists(runId)
                && workspaceId != null
                && workspaceId.equals(workspaceByRun.get(runId))
                && conversationId != null
                && conversationId.equals(conversationByRun.get(runId));
    }

    public List<AgentRunEvent> snapshot(String runId) {
        return new ArrayList<>(eventsByRun.getOrDefault(runId, new CopyOnWriteArrayList<>()));
    }
}

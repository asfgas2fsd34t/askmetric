package dev.askmetric.server.agent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 管理当前实例上的 SSE 连接；事件历史和顺序以数据库为准。 */
@Component
public class AgentRunSseHub {
    private final Map<String, Set<Subscription>> subscriptionsByRun = new HashMap<>();

    public synchronized void registerAndReplay(
            String runId,
            long afterSequence,
            SseEmitter emitter,
            Supplier<List<AgentRunEvent>> history) {
        var subscription = new Subscription(emitter, afterSequence);
        subscriptionsByRun.computeIfAbsent(runId, ignored -> new HashSet<>()).add(subscription);
        emitter.onCompletion(() -> remove(runId, subscription));
        emitter.onTimeout(() -> remove(runId, subscription));
        emitter.onError(ignored -> remove(runId, subscription));
        try {
            sendMissing(runId, subscription, history.get());
        } catch (RuntimeException exception) {
            remove(runId, subscription);
            throw exception;
        }
    }

    public synchronized void publishPersisted(String runId, Supplier<List<AgentRunEvent>> history) {
        var subscriptions = subscriptionsByRun.get(runId);
        if (subscriptions == null || subscriptions.isEmpty()) {
            return;
        }
        var events = history.get();
        for (Subscription subscription : List.copyOf(subscriptions)) {
            sendMissing(runId, subscription, events);
        }
    }

    private void sendMissing(String runId, Subscription subscription, List<AgentRunEvent> events) {
        for (AgentRunEvent event : events) {
            if (event.getSequence() <= subscription.lastSequence) {
                continue;
            }
            try {
                subscription.emitter.send(SseEmitter.event()
                        .id(Long.toString(event.getSequence()))
                        .name(event.getEventType().wireValue())
                        .data(event));
                subscription.lastSequence = event.getSequence();
                if (event.getEventType().isTerminal()) {
                    subscription.emitter.complete();
                    remove(runId, subscription);
                    return;
                }
            } catch (Exception exception) {
                remove(runId, subscription);
                subscription.emitter.completeWithError(exception);
                return;
            }
        }
    }

    private synchronized void remove(String runId, Subscription subscription) {
        var subscriptions = subscriptionsByRun.get(runId);
        if (subscriptions == null) {
            return;
        }
        subscriptions.remove(subscription);
        if (subscriptions.isEmpty()) {
            subscriptionsByRun.remove(runId);
        }
    }

    private static final class Subscription {
        private final SseEmitter emitter;
        private long lastSequence;

        private Subscription(SseEmitter emitter, long lastSequence) {
            this.emitter = emitter;
            this.lastSequence = lastSequence;
        }
    }
}

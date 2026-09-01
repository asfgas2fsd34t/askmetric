package dev.askmetric.server.conversation;

import dev.askmetric.server.agent.AgentRunEventSource;
import dev.askmetric.server.agent.AgentRunEventType;
import dev.askmetric.server.agent.AgentRunIntentRoute;
import dev.askmetric.server.agent.AgentRunMapper;
import dev.askmetric.server.agent.PersistedAgentRun;
import java.util.List;
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
    private final DeterministicChatReply deterministicChatReply;

    ConversationService(
            ConversationMapper mapper,
            AgentRunMapper agentRunMapper,
            DeterministicChatReply deterministicChatReply) {
        this.mapper = mapper;
        this.agentRunMapper = agentRunMapper;
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
        return snapshot;
    }

    @Transactional
    public ChatMessageCompleted submitChatMessage(
            String userSubject,
            String workspaceId,
            String conversationId,
            CreateMessageRequest request) {
        String content = requiredText(request == null ? null : request.getContent(), "content", MAX_MESSAGE_LENGTH);
        ConversationMessage userMessage = mapper.appendMessage(
                        userSubject,
                        workspaceId,
                        conversationId,
                        "message_" + UUID.randomUUID(),
                        "user",
                        userSubject,
                        content)
                .orElseThrow(() -> new AccessDeniedException("Conversation not found in Workspace"));
        String runId = "run_" + UUID.randomUUID();
        if (agentRunMapper.createChatRun(
                        userSubject,
                        workspaceId,
                        conversationId,
                        runId,
                        userMessage.getMessageId(),
                        AgentRunIntentRoute.CHAT)
                != 1) {
            throw new AccessDeniedException("Conversation not found in Workspace");
        }
        appendAuditEvent(userSubject, workspaceId, runId, 1, AgentRunEventType.ACCEPTED, "普通聊天 Agent Run 已接受");
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
        appendAuditEvent(userSubject, workspaceId, runId, 3, AgentRunEventType.COMPLETED, "普通聊天 Agent Run 已完成");
        PersistedAgentRun agentRun = agentRunMapper.runs(userSubject, workspaceId, conversationId).stream()
                .filter(candidate -> runId.equals(candidate.getRunId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Persisted Agent Run was not found"));
        agentRun.setAuditEvents(agentRunMapper.auditEvents(userSubject, workspaceId, runId));
        return new ChatMessageCompleted(userMessage, assistantMessage, agentRun);
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

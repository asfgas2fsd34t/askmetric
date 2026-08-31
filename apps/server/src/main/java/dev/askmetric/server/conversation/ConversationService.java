package dev.askmetric.server.conversation;

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

    ConversationService(ConversationMapper mapper) {
        this.mapper = mapper;
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
        return snapshot;
    }

    @Transactional
    public ConversationMessage appendUserMessage(
            String userSubject,
            String workspaceId,
            String conversationId,
            CreateMessageRequest request) {
        String content = requiredText(request == null ? null : request.getContent(), "content", MAX_MESSAGE_LENGTH);
        return mapper.appendUserMessage(
                        userSubject,
                        workspaceId,
                        conversationId,
                        "message_" + UUID.randomUUID(),
                        content)
                .orElseThrow(() -> new AccessDeniedException("Conversation not found in Workspace"));
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

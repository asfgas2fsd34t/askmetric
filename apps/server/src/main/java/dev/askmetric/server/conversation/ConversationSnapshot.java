package dev.askmetric.server.conversation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ConversationSnapshot {
    private String conversationId;
    private String workspaceId;
    private String title;
    private Instant createdAt;
    private Instant updatedAt;
    private List<ConversationMessage> messages = new ArrayList<>();
}

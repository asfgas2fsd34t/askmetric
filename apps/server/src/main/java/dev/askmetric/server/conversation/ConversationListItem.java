package dev.askmetric.server.conversation;

import java.time.Instant;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ConversationListItem {
    private String conversationId;
    private String title;
    private long messageCount;
    private Instant createdAt;
    private Instant updatedAt;
}

package dev.askmetric.server.conversation;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class CreateMessageRequest {
    private String content;
}

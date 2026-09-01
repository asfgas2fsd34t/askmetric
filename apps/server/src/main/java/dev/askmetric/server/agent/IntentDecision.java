package dev.askmetric.server.agent;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class IntentDecision {
    private AgentRunIntentRoute route;
    private BigDecimal confidence;
}

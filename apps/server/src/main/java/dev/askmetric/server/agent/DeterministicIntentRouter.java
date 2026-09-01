package dev.askmetric.server.agent;

import java.math.BigDecimal;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class DeterministicIntentRouter {
    public IntentDecision route(String message) {
        String normalized = message.toLowerCase(Locale.ROOT);
        if (normalized.contains("mrr") || normalized.contains("月度经常性收入")) {
            return new IntentDecision(AgentRunIntentRoute.ANALYSIS, new BigDecimal("0.950"));
        }
        if (normalized.matches(".*(分析|趋势|指标|下降|上升|增长|同比|环比|原因|为什么).*")
                && !normalized.matches(".*(你是谁|你能做什么|你好|嗨|hello|hi).*") ) {
            return new IntentDecision(AgentRunIntentRoute.ANALYSIS, new BigDecimal("0.800"));
        }
        if (normalized.matches(".*(你好|嗨|hello|hi|你是谁|你能做什么|什么是 askmetric|askmetric 是什么).*")) {
            return new IntentDecision(AgentRunIntentRoute.CHAT, BigDecimal.ONE);
        }
        return new IntentDecision(AgentRunIntentRoute.CHAT, new BigDecimal("0.500"));
    }
}

package dev.askmetric.server.agent;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class DeterministicIntentRouter {
    private static final List<String> CONTINUATION_PREFIXES = List.of("继续", "补充", "按", "只看", "聚焦", "细分");
    private static final List<String> SWITCH_PREFIXES = List.of("切换", "改为", "换成", "转为", "另起");

    public IntentDecision route(String message) {
        String normalized = message.toLowerCase(Locale.ROOT);
        AnalysisTaskCommand taskCommand = taskCommand(normalized);
        if (taskCommand != AnalysisTaskCommand.NONE) {
            return new IntentDecision(AgentRunIntentRoute.ANALYSIS, BigDecimal.ONE, taskCommand);
        }
        if (normalized.contains("mrr") || normalized.contains("月度经常性收入")) {
            return new IntentDecision(AgentRunIntentRoute.ANALYSIS, new BigDecimal("0.950"), AnalysisTaskCommand.NONE);
        }
        if (normalized.matches(".*(分析|趋势|指标|下降|上升|增长|同比|环比|原因|为什么).*")
                && !normalized.matches(".*(你是谁|你能做什么|你好|嗨|hello|hi).*") ) {
            return new IntentDecision(AgentRunIntentRoute.ANALYSIS, new BigDecimal("0.800"), AnalysisTaskCommand.NONE);
        }
        if (normalized.matches(".*(你好|嗨|hello|hi|你是谁|你能做什么|什么是 askmetric|askmetric 是什么).*")) {
            return new IntentDecision(AgentRunIntentRoute.CHAT, BigDecimal.ONE, AnalysisTaskCommand.NONE);
        }
        return new IntentDecision(AgentRunIntentRoute.CHAT, new BigDecimal("0.500"), AnalysisTaskCommand.NONE);
    }

    private static AnalysisTaskCommand taskCommand(String message) {
        if (matchingPrefix(message, CONTINUATION_PREFIXES) != null) {
            return AnalysisTaskCommand.CONTINUE;
        }
        String switchPrefix = matchingPrefix(message, SWITCH_PREFIXES);
        if (switchPrefix != null) {
            String target = message.substring(switchPrefix.length()).strip();
            if (target.startsWith("到")) {
                target = target.substring(1).strip();
            }
            return target.isEmpty() ? AnalysisTaskCommand.INCOMPLETE : AnalysisTaskCommand.SWITCH;
        }
        return AnalysisTaskCommand.NONE;
    }

    private static String matchingPrefix(String message, List<String> prefixes) {
        return prefixes.stream().filter(message::startsWith).findFirst().orElse(null);
    }
}

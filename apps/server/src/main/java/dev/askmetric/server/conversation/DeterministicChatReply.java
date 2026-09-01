package dev.askmetric.server.conversation;

import java.util.Locale;
import org.springframework.stereotype.Component;

/** 无模型凭据时为普通对话提供可重复的基础回复。 */
@Component
class DeterministicChatReply {
    private static final String GREETING_REPLY = "你好，我是 AskMetric。你可以向我询问业务指标、数据口径或分析目标。";
    private static final String PRODUCT_REPLY = "AskMetric 将业务问题转化为可审计分析，并在需要时生成独立 HTML 报告。";
    private static final String DEFAULT_REPLY = "我是 AskMetric。你可以描述业务问题、指标口径或希望调查的分析目标。";

    String replyTo(String message) {
        String normalized = message.toLowerCase(Locale.ROOT);
        if (normalized.matches("^(你好|您好|嗨|hello|hi)[!！,.， ]*$")) {
            return GREETING_REPLY;
        }
        if (normalized.contains("你是谁")
                || normalized.contains("什么是 askmetric")
                || normalized.contains("askmetric 是什么")
                || normalized.contains("what is askmetric")) {
            return PRODUCT_REPLY;
        }
        return DEFAULT_REPLY;
    }
}

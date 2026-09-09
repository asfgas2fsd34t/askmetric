package dev.askmetric.server.analysis;

/**
 * 分析引用的知识段落：来源标识 + 段落序号 + 引文。
 * 引文是不可信数据，仅用于展示与追溯。
 */
public record KnowledgeCitation(String knowledgeSourceId, int passageNumber, String quote) {
    public KnowledgeCitation {
        if (knowledgeSourceId == null || knowledgeSourceId.isBlank()) {
            throw new IllegalArgumentException("knowledgeSourceId must not be blank");
        }
        if (passageNumber < 1) {
            throw new IllegalArgumentException("passageNumber must be positive");
        }
        if (quote == null || quote.isBlank() || quote.length() > 500) {
            throw new IllegalArgumentException("quote must be 1-500 characters");
        }
    }
}

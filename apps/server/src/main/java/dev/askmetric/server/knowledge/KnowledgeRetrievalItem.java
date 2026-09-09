package dev.askmetric.server.knowledge;

/** 分析引用知识时使用的检索结果条目：来源、段落号与引文，只包含引用数据。 */
public record KnowledgeRetrievalItem(
        String knowledgeSourceId,
        String title,
        int passageNumber,
        String quote) {
}

package dev.askmetric.server.knowledge;

import lombok.Data;

/** knowledge_passage 表行。 */
@Data
public class KnowledgePassage {
    private String knowledgePassageId;
    private String knowledgeSourceId;
    private String workspaceId;
    private int passageNumber;
    private String text;
}

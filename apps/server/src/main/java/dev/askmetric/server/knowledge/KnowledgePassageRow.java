package dev.askmetric.server.knowledge;

import lombok.Data;

/** 检索用的段落投影：来源、标题、序号与文本。 */
@Data
public class KnowledgePassageRow {
    private String knowledgeSourceId;
    private String title;
    private int passageNumber;
    private String text;
}

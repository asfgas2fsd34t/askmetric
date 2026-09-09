package dev.askmetric.server.knowledge;

import java.time.Instant;
import lombok.Data;

/**
 * 工作区知识来源及其摄取生命周期。
 * 摄取后的内容是不可信数据：只能以引用形式进入分析，不能影响权限、策略或工具行为。
 */
@Data
public class KnowledgeSource {
    public enum Status {
        UPLOADED, READY, FAILED
    }

    private String knowledgeSourceId;
    private String workspaceId;
    private String title;
    private String filename;
    private String contentType;
    private long byteSize;
    private Status status;
    private String failureReason;
    private int passageCount;
    private Instant createdAt;
    private Instant ingestedAt;
}

package dev.askmetric.server.evidence;

import java.time.Instant;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 一次成功治理查询产生的不可变证据快照持久化记录。 */
@Data
@NoArgsConstructor
public class EvidenceSnapshotRecord {
    /** 证据快照的全局唯一标识。 */
    private String evidenceSnapshotId;
    /** 所属工作区。 */
    private String workspaceId;
    /** 所属 Conversation 标识。 */
    private String conversationId;
    /** 关联的 Analysis Task 标识。 */
    private String analysisTaskId;
    /** 关联的 Agent Run 标识。 */
    private String runId;
    /** 关联的查询审计记录标识。 */
    private String queryId;
    /** 证据使用的来源表。 */
    private String sourceTable;
    /** 证据引用的数据范围。 */
    private String sourceRange;
    /** 证据列 JSON，由 Java 策略生成。 */
    private String columnsJson;
    /** 证据行 JSON，由 Java 策略生成。 */
    private String rowsJson;
    /** 证据行数。 */
    private int rowCount;
    /** 查询耗时，单位为毫秒。 */
    private long durationMs;
    /** 证据内容的确定性哈希，用于检测存储损坏或未授权变更。 */
    private String snapshotHash;
    /** 创建时间。 */
    private Instant createdAt;
}

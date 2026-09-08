package dev.askmetric.server.evidence;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 对话快照返回给前端的不可变证据摘要。 */
@Data
@NoArgsConstructor
public class EvidenceSnapshot {
    /** 证据快照的全局唯一标识。 */
    private String evidenceSnapshotId;
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
    /** 证据包含的策略允许列。 */
    private List<String> columns = List.of();
    /** 证据包含的策略允许行。 */
    private List<Map<String, Object>> rows = List.of();
    /** 证据行数。 */
    private int rowCount;
    /** 查询耗时，单位为毫秒。 */
    private long durationMs;
    /** 证据快照创建时间。 */
    private Instant createdAt;
}

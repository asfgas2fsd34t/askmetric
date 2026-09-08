package dev.askmetric.server.query;

import lombok.Data;
import lombok.NoArgsConstructor;

/** 一次受治理查询的不可变审计记录。 */
@Data
@NoArgsConstructor
public class QueryAuditRecord {
    /** 查询审计记录标识。 */
    private String queryId;
    /** 查询所属工作区。 */
    private String workspaceId;
    /** 发起查询的认证主体。 */
    private String userSubject;
    /** 关联分析任务。 */
    private String analysisTaskId;
    /** 关联 Agent Run。 */
    private String runId;
    /** 原始 SQL 模板。 */
    private String sql;
    /** 参数个数，不保存敏感参数值。 */
    private int parameterCount;
    /** 查询最终状态。 */
    private QueryStatus status;
    /** 拒绝或失败原因。 */
    private String reason;
    /** 执行耗时，单位为毫秒。 */
    private long durationMs;
    /** 返回行数。 */
    private int rowCount;
}

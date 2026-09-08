package dev.askmetric.server.query;

import java.util.List;
import java.util.Map;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 受策略限制后返回给 Agent 的查询结果。 */
@Data
@NoArgsConstructor
public class QueryResult {
    /** 查询审计记录标识。 */
    private String queryId;
    /** 查询最终状态。 */
    private QueryStatus status;
    /** 结果列名。 */
    private List<String> columns = List.of();
    /** 结果行；只包含策略允许的数据。 */
    private List<Map<String, Object>> rows = List.of();
    /** 实际返回行数。 */
    private int rowCount;
    /** 查询耗时，单位为毫秒。 */
    private long durationMs;
    /** 被拒绝或执行失败时的原因。 */
    private String reason;
}

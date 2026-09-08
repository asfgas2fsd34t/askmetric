package dev.askmetric.server.query;

import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Python 或其他受信调用方提交的受治理查询请求。 */
@Data
@NoArgsConstructor
public class QueryRequest {
    /** 发起查询的工作区。 */
    private String workspaceId;
    /** 关联的 Analysis Task，可为空。 */
    private String analysisTaskId;
    /** 关联的 Agent Run，可为空。 */
    private String runId;
    /** 只读 SQL 模板，参数必须使用 PostgreSQL 问号占位符。 */
    private String sql;
    /** 按 SQL 占位符顺序提供的参数。 */
    private List<Object> parameters = List.of();
}

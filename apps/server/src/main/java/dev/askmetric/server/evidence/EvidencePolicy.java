package dev.askmetric.server.evidence;

import dev.askmetric.server.query.QueryResult;
import dev.askmetric.server.query.QueryValidationException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 将查询结果裁剪为 Agent 可接收、报告可展示的数据，并生成证据引用范围。 */
@Component
public class EvidencePolicy {
    private static final int MAX_TEXT_LENGTH = 4000;
    /** 首版语义目录的字段分级；仅这些字段允许进入 Agent 上下文和报告。 */
    private static final Set<String> AGENT_VISIBLE_COLUMNS = Set.of(
            "dataset_version", "period_start", "period_end", "currency", "segment_code", "segment_name",
            "display_order", "plan_code", "plan_name", "base_monthly_price_cents", "created_on",
            "is_synthetic", "event_date", "event_type", "mrr_delta_cents", "is_planted", "description",
            "month_start", "ending_mrr_cents");

    /** 就地裁剪 QueryResult；任何不可见字段都会被拒绝，避免调用方拿到半份结果。 */
    public void apply(QueryResult result, String sourceTable) {
        if (sourceTable == null || sourceTable.isBlank()) {
            throw new QueryValidationException("查询缺少证据来源");
        }
        List<Map<String, Object>> rows = result.getRows();
        if (rows == null) {
            rows = List.of();
        }
        List<String> columns = result.getColumns() == null || result.getColumns().isEmpty()
                ? List.copyOf(rows.isEmpty()
                        ? List.of()
                        : rows.getFirst().keySet())
                : List.copyOf(result.getColumns());
        for (String column : columns) {
            if (!AGENT_VISIBLE_COLUMNS.contains(column)) {
                throw new QueryValidationException("查询结果包含 Agent 不可见字段: " + column);
            }
        }
        List<Map<String, Object>> governedRows = new ArrayList<>(rows.size());
        for (Map<String, Object> sourceRow : rows) {
            governedRows.add(governedRow(columns, sourceRow));
        }
        result.setColumns(columns);
        result.setRows(governedRows);
        result.setRowCount(governedRows.size());
        result.setSourceTable(sourceTable);
        result.setSourceRange(sourceRange(columns, governedRows));
    }

    private static Map<String, Object> governedRow(List<String> columns, Map<String, Object> sourceRow) {
        Map<String, Object> governedRow = new LinkedHashMap<>();
        for (String column : columns) {
            governedRow.put(column, governedValue(column, sourceRow.get(column)));
        }
        return governedRow;
    }

    private static Object governedValue(String column, Object value) {
        if (value == null) {
            return null;
        }
        Object normalized = normalize(column, value);
        if (normalized instanceof String text && text.length() > MAX_TEXT_LENGTH) {
            throw new QueryValidationException("查询结果字段值超过策略限制: " + column);
        }
        return normalized;
    }

    private static Object normalize(String column, Object value) {
        if (value instanceof LocalDate date) {
            return date.format(DateTimeFormatter.ISO_LOCAL_DATE);
        }
        if (value instanceof LocalDateTime timestamp) {
            return timestamp.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }
        if (value instanceof OffsetDateTime timestamp) {
            return timestamp.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }
        if (value instanceof String || value instanceof Boolean || value instanceof Integer
                || value instanceof Long || value instanceof Short || value instanceof Byte
                || value instanceof Float || value instanceof Double || value instanceof BigDecimal) {
            return value;
        }
        throw new QueryValidationException("查询结果包含不支持的字段值: " + column);
    }

    private static String sourceRange(List<String> columns, List<Map<String, Object>> rows) {
        List<String> temporalColumns = columns.stream()
                .filter(column -> column.equals("period_start") || column.equals("period_end")
                        || column.equals("created_on") || column.equals("event_date")
                        || column.equals("month_start"))
                .toList();
        if (rows.isEmpty()) {
            return "无返回行";
        }
        if (temporalColumns.isEmpty()) {
            return "共 " + rows.size() + " 行";
        }
        String first = temporalColumns.getFirst();
        String last = temporalColumns.getLast();
        Object start = rows.getFirst().get(first);
        Object end = rows.getLast().get(last);
        return start == null || end == null
                ? "时间字段存在空值"
                : start + " 至 " + end;
    }
}

package dev.askmetric.server.query;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 对首版 Demo Warehouse 执行保守的单语句只读 SQL 校验。 */
@Component
public class QuerySqlValidator {
    private static final Pattern TABLE_REFERENCE = Pattern.compile(
            "\\b(?:from|join)\\s+([a-z_][a-z0-9_]*(?:\\.[a-z_][a-z0-9_]*)?)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CTE_NAME = Pattern.compile(
            "\\bwith\\s+([a-z_][a-z0-9_]*)\\s+as\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern PARAMETER = Pattern.compile("\\?");
    private static final Pattern SELECT_LIST = Pattern.compile(
            "(?is)\\bselect\\s+(.*?)\\s+from\\b");
    private static final Pattern IDENTIFIER = Pattern.compile("\\b[a-z_][a-z0-9_]*\\b");
    private static final Set<String> REGISTERED_TABLES = Set.of(
            "demo_warehouse.dataset_manifest",
            "demo_warehouse.customer_segment",
            "demo_warehouse.subscription_plan",
            "demo_warehouse.customer_account",
            "demo_warehouse.subscription_event",
            "demo_warehouse.monthly_mrr");
    private static final Set<String> REGISTERED_COLUMNS = Set.of(
            "dataset_version", "period_start", "period_end", "currency", "segment_code", "segment_name",
            "display_order", "plan_code", "plan_name", "base_monthly_price_cents", "customer_id", "account_name",
            "created_on", "is_synthetic", "event_id", "event_date", "event_type", "mrr_delta_cents",
            "is_planted", "description", "month_start", "ending_mrr_cents");
    private static final Set<String> SQL_IDENTIFIERS = Set.of(
            "as", "distinct", "count", "sum", "avg", "min", "max", "coalesce", "case", "when", "then",
            "else", "end", "cast", "date", "interval", "true", "false", "null");
    private static final Set<String> RESTRICTED_IDENTIFIERS = Set.of(
            "password", "secret", "token", "credential", "email", "phone");

    /** 校验单条 SELECT/只读 CTE，并返回参数占位符数量。 */
    public int validate(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new QueryValidationException("SQL 不能为空");
        }
        String normalized = sql.strip();
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (normalized.indexOf(';') >= 0 || lower.contains("--") || lower.contains("/*") || lower.contains("*/")) {
            throw new QueryValidationException("不允许多语句或 SQL 注释");
        }
        if (!(lower.startsWith("select") || lower.startsWith("with"))) {
            throw new QueryValidationException("只允许 SELECT 或只读 CTE");
        }
        if (lower.matches("(?s).*\\bselect\\s+\\*.*")) {
            throw new QueryValidationException("不允许使用未展开字段的通配符");
        }
        if (lower.matches("(?s).*\\b(insert|update|delete|merge|drop|alter|create|truncate|grant|revoke|copy|call|do)\\b.*")) {
            throw new QueryValidationException("查询包含非只读语句");
        }
        for (String identifier : RESTRICTED_IDENTIFIERS) {
            if (lower.matches("(?s).*\\b" + identifier + "\\b.*")) {
                throw new QueryValidationException("查询访问受限字段");
            }
        }
        Matcher tables = TABLE_REFERENCE.matcher(lower);
        Set<String> cteNames = new java.util.HashSet<>();
        Matcher ctes = CTE_NAME.matcher(lower);
        while (ctes.find()) {
            cteNames.add(ctes.group(1));
        }
        int tableCount = 0;
        while (tables.find()) {
            tableCount++;
            if (!REGISTERED_TABLES.contains(tables.group(1)) && !cteNames.contains(tables.group(1))) {
                throw new QueryValidationException("表未登记在语义目录中: " + tables.group(1));
            }
        }
        if (tableCount == 0) {
            throw new QueryValidationException("查询必须访问语义目录登记的数据表");
        }
        validateSelectedColumns(lower);
        return (int) PARAMETER.matcher(normalized).results().count();
    }

    private static void validateSelectedColumns(String sql) {
        Matcher select = SELECT_LIST.matcher(sql);
        if (!select.find()) {
            return;
        }
        Matcher identifiers = IDENTIFIER.matcher(select.group(1));
        while (identifiers.find()) {
            String identifier = identifiers.group();
            if (!REGISTERED_COLUMNS.contains(identifier) && !SQL_IDENTIFIERS.contains(identifier)) {
                throw new QueryValidationException("字段未登记在语义目录中: " + identifier);
            }
        }
    }
}

package dev.askmetric.server.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.askmetric.server.query.QueryResult;
import dev.askmetric.server.query.QueryValidationException;
import java.sql.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvidencePolicyTest {
    private final EvidencePolicy policy = new EvidencePolicy();

    @Test
    void keepsAgentVisibleColumnsAndBuildsSourceRange() {
        QueryResult result = result(
                List.of("month_start", "ending_mrr_cents"),
                List.of(
                        row(Date.valueOf("2025-01-01"), 320000L),
                        row(Date.valueOf("2025-06-01"), 190000L)));

        policy.apply(result, "demo_warehouse.monthly_mrr");

        assertThat(result.getColumns()).containsExactly("month_start", "ending_mrr_cents");
        assertThat(result.getRows()).isEqualTo(List.of(
                Map.of("month_start", "2025-01-01", "ending_mrr_cents", 320000L),
                Map.of("month_start", "2025-06-01", "ending_mrr_cents", 190000L)));
        assertThat(result.getSourceTable()).isEqualTo("demo_warehouse.monthly_mrr");
        assertThat(result.getSourceRange()).isEqualTo("2025-01-01 至 2025-06-01");
    }

    @Test
    void describesAnEmptyResultWithoutInventingARange() {
        QueryResult result = result(List.of(), List.of());

        policy.apply(result, "demo_warehouse.monthly_mrr");

        assertThat(result.getSourceRange()).isEqualTo("无返回行");
    }

    @Test
    void followsGovernedColumnsAndDropsHiddenRowFields() {
        Map<String, Object> sourceRow = new LinkedHashMap<>();
        sourceRow.put("month_start", "2025-01-01");
        sourceRow.put("customer_id", "customer-hidden");
        sourceRow.put("ending_mrr_cents", 320000L);
        QueryResult result = result(
                List.of("month_start", "ending_mrr_cents"), List.of(sourceRow));

        policy.apply(result, "demo_warehouse.monthly_mrr");

        assertThat(result.getRows()).isEqualTo(List.of(
                Map.of("month_start", "2025-01-01", "ending_mrr_cents", 320000L)));
    }

    @Test
    void rejectsIdentifierColumnsBeforeEvidenceIsReturned() {
        QueryResult result = result(List.of("customer_id"), List.of(row("customer-acme", null)));

        assertThatThrownBy(() -> policy.apply(result, "demo_warehouse.customer_account"))
                .isInstanceOf(QueryValidationException.class)
                .hasMessage("查询结果包含 Agent 不可见字段: customer_id");
    }

    @Test
    void rejectsUnsupportedValuesAndOversizedText() {
        QueryResult unsupported = result(List.of("description"), List.of(Map.of("description", new byte[]{1})));
        assertThatThrownBy(() -> policy.apply(unsupported, "demo_warehouse.subscription_event"))
                .isInstanceOf(QueryValidationException.class)
                .hasMessage("查询结果包含不支持的字段值: description");

        QueryResult oversized = result(List.of("description"), List.of(Map.of("description", "x".repeat(4001))));
        assertThatThrownBy(() -> policy.apply(oversized, "demo_warehouse.subscription_event"))
                .isInstanceOf(QueryValidationException.class)
                .hasMessage("查询结果字段值超过策略限制: description");
    }

    private static QueryResult result(List<String> columns, List<Map<String, Object>> rows) {
        QueryResult result = new QueryResult();
        result.setColumns(columns);
        result.setRows(rows);
        result.setRowCount(rows.size());
        return result;
    }

    private static Map<String, Object> row(Object monthStart, Object endingMrr) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("month_start", monthStart);
        row.put("ending_mrr_cents", endingMrr);
        return row;
    }
}

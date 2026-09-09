package dev.askmetric.server.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class QuerySqlValidatorTest {
    private final QuerySqlValidator validator = new QuerySqlValidator();

    @Test
    void acceptsRegisteredSelectAndCountsParameters() {
        assertThat(validator.validate("select month_start, ending_mrr_cents from demo_warehouse.monthly_mrr where month_start >= ?"))
                .isEqualTo(1);
    }

    @Test
    void acceptsReadOnlyCte() {
        assertThat(validator.validate("with mrr as (select month_start, ending_mrr_cents from demo_warehouse.monthly_mrr) "
                + "select month_start from mrr"))
                .isZero();
    }

    @Test
    void rejectsWritesCommentsAndMultipleStatements() {
        assertRejected("delete from demo_warehouse.monthly_mrr", "SELECT");
        assertRejected("select month_start from demo_warehouse.monthly_mrr; drop table x", "多语句");
        assertRejected("select month_start from demo_warehouse.monthly_mrr -- bypass", "多语句");
    }

    @Test
    void rejectsUnregisteredTablesFieldsAndRestrictedFields() {
        assertRejected("select month_start from public.monthly_mrr", "表未登记");
        assertRejected("select unknown_metric from demo_warehouse.monthly_mrr", "字段未登记");
        assertRejected("select secret from demo_warehouse.monthly_mrr", "受限字段");
        assertRejected("select * from demo_warehouse.monthly_mrr", "通配符");
    }

    @Test
    void acceptsTableQualifiedColumnsInJoinedDrilldownQueries() {
        String sql = """
                select segment.segment_code, event.plan_code, event.event_type, event.mrr_delta_cents
                from demo_warehouse.subscription_event event
                join demo_warehouse.customer_account account on account.customer_id = event.customer_id
                join demo_warehouse.customer_segment segment on segment.segment_code = account.segment_code
                where event.event_date >= '2025-06-01' and event.event_date < '2025-07-01'
                """;

        assertThat(validator.validate(sql)).isZero();
    }

    @Test
    void rejectsUnregisteredColumnsAfterAQualifier() {
        assertRejected(
                "select segment.unknown_metric from demo_warehouse.customer_segment segment",
                "字段未登记");
    }

    @Test
    void rejectsQualifiedWildcards() {
        assertRejected(
                "select event.* from demo_warehouse.subscription_event event",
                "通配符");
        assertThat(validator.validate(
                "select count(*) from demo_warehouse.subscription_event")).isZero();
    }

    private void assertRejected(String sql, String message) {
        assertThatThrownBy(() -> validator.validate(sql))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining(message);
    }
}

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

    private void assertRejected(String sql, String message) {
        assertThatThrownBy(() -> validator.validate(sql))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining(message);
    }
}

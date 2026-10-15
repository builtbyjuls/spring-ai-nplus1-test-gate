package com.gridsettlement.gate.normalizer;

import com.gridsettlement.gate.interceptor.RecordedQuery;
import net.ttddyy.dsproxy.proxy.ParameterSetOperation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqlNormalizerTest {

    @Test
    @DisplayName("Should detect SELECT queries and reject non-SELECT queries")
    void shouldDetectSelectQueries() {
        assertThat(SqlNormalizer.isSelect("SELECT * FROM contracts")).isTrue();
        assertThat(SqlNormalizer.isSelect("  select id, name from users")).isTrue();
        assertThat(SqlNormalizer.isSelect("\n\tSELECT 1")).isTrue();

        assertThat(SqlNormalizer.isSelect("INSERT INTO contracts VALUES (1)")).isFalse();
        assertThat(SqlNormalizer.isSelect("UPDATE contracts SET status = 'ACTIVE'")).isFalse();
        assertThat(SqlNormalizer.isSelect("DELETE FROM contracts WHERE id = 1")).isFalse();
        assertThat(SqlNormalizer.isSelect(null)).isFalse();
        assertThat(SqlNormalizer.isSelect("")).isFalse();
    }

    @Test
    @DisplayName("Should detect sequence calls matching nextval and currval")
    void shouldDetectSequenceCalls() {
        assertThat(SqlNormalizer.isSequenceQuery("select nextval('contract_seq')")).isTrue();
        assertThat(SqlNormalizer.isSequenceQuery("SELECT NEXTVAL('trade_id_seq')")).isTrue();
        assertThat(SqlNormalizer.isSequenceQuery("  select currval('contract_seq')")).isTrue();
        assertThat(SqlNormalizer.isSequenceQuery("select nextval(pg_get_serial_sequence('tbl', 'id'))")).isTrue();

        assertThat(SqlNormalizer.isSequenceQuery("SELECT id, nextval_col FROM my_table")).isFalse();
        assertThat(SqlNormalizer.isSequenceQuery("SELECT * FROM bilateral_contract WHERE id = ?")).isFalse();
        assertThat(SqlNormalizer.isSequenceQuery(null)).isFalse();
    }

    @Test
    @DisplayName("Should collapse variable IN (?, ?, ...) lists to single IN (?) and collapse whitespace")
    void shouldCollapseInListsAndWhitespace() {
        String query2 = "SELECT c.id FROM contract c WHERE c.status IN (?, ?)   ORDER BY c.id;";
        String query4 = "SELECT c.id FROM contract c WHERE c.status IN (?, ?, ?, ?) ORDER BY c.id";
        String multiline = "SELECT c.id \n FROM contract c \n WHERE c.status IN (\n ?,\n ? \n) ORDER BY c.id";

        String normalized2 = SqlNormalizer.normalize(query2);
        String normalized4 = SqlNormalizer.normalize(query4);
        String normalizedMulti = SqlNormalizer.normalize(multiline);

        assertThat(normalized2).isEqualTo("SELECT c.id FROM contract c WHERE c.status IN (?) ORDER BY c.id");
        assertThat(normalized4).isEqualTo("SELECT c.id FROM contract c WHERE c.status IN (?) ORDER BY c.id");
        assertThat(normalizedMulti).isEqualTo("SELECT c.id FROM contract c WHERE c.status IN (?) ORDER BY c.id");
    }

    @Test
    @DisplayName("Should accurately identify OFFSET parameter index when preceded by other parameters")
    void shouldFindOffsetParameterIndex() {
        String sql = "SELECT * FROM contracts WHERE status = ? AND buyer_id = ? ORDER BY id LIMIT ? OFFSET ?";
        int index = SqlNormalizer.findOffsetParameterIndex(sql);

        // status=? (1), buyer_id=? (2), LIMIT ? (3) -> OFFSET ? is at index 4
        assertThat(index).isEqualTo(4);

        String sqlOffsetFirst = "SELECT * FROM contracts WHERE id > ? OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";
        assertThat(SqlNormalizer.findOffsetParameterIndex(sqlOffsetFirst)).isEqualTo(2);

        String sqlNoOffset = "SELECT * FROM contracts WHERE id = ?";
        assertThat(SqlNormalizer.findOffsetParameterIndex(sqlNoOffset)).isEqualTo(-1);
    }

    @Test
    @DisplayName("Should detect pagination when offset strictly increases across executions")
    void shouldDetectPaginationWhenOffsetIncreases() throws Exception {
        String sql = "SELECT * FROM items WHERE tenant_id = ? ORDER BY id LIMIT ? OFFSET ?";
        Method setIntMethod = java.sql.PreparedStatement.class.getMethod("setInt", int.class, int.class);

        RecordedQuery q1 = createQueryWithOffset(sql, setIntMethod, 3, 0);
        RecordedQuery q2 = createQueryWithOffset(sql, setIntMethod, 3, 10);
        RecordedQuery q3 = createQueryWithOffset(sql, setIntMethod, 3, 20);

        assertThat(SqlNormalizer.isPagination(List.of(q1, q2, q3))).isTrue();

        // Non-increasing offset (e.g. constant offset 0) should NOT be considered pagination
        RecordedQuery qConst1 = createQueryWithOffset(sql, setIntMethod, 3, 0);
        RecordedQuery qConst2 = createQueryWithOffset(sql, setIntMethod, 3, 0);
        assertThat(SqlNormalizer.isPagination(List.of(qConst1, qConst2))).isFalse();
    }

    private RecordedQuery createQueryWithOffset(String sql, Method method, int paramIndex, int offsetValue) {
        ParameterSetOperation op = new ParameterSetOperation(method, new Object[]{paramIndex, offsetValue});
        return new RecordedQuery(sql, List.of(op), List.of(), false, Instant.now());
    }
}

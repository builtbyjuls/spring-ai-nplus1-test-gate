package com.builtbyjuls.gate.interceptor;

import com.builtbyjuls.gate.config.NPlusOneDataSourceBeanPostProcessor;
import net.ttddyy.dsproxy.proxy.ParameterSetOperation;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NPlusOneDataSourceInterceptionTest {

    private DataSource proxyDataSource;

    @BeforeEach
    void setUp() throws SQLException {
        NPlusOneGateScope.reset();
        NPlusOneGateScope.setDiagnosticMode(false);

        JdbcDataSource rawDataSource = new JdbcDataSource();
        rawDataSource.setURL("jdbc:h2:mem:interception_test;DB_CLOSE_DELAY=-1");
        rawDataSource.setUser("sa");
        rawDataSource.setPassword("");

        NPlusOneQueryExecutionListener listener = new NPlusOneQueryExecutionListener("com.builtbyjuls.gate");
        NPlusOneDataSourceBeanPostProcessor postProcessor = new NPlusOneDataSourceBeanPostProcessor(listener);

        proxyDataSource = (DataSource) postProcessor.postProcessAfterInitialization(rawDataSource, "dataSource");

        try (Connection conn = proxyDataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS test_record (" +
                    "id INT PRIMARY KEY, " +
                    "name VARCHAR(100), " +
                    "nullable_col VARCHAR(50), " +
                    "amount DECIMAL(18, 6))");
        }
        NPlusOneGateScope.reset();
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection conn = proxyDataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS test_record");
        }
        NPlusOneGateScope.reset();
        NPlusOneGateScope.setDiagnosticMode(false);
    }

    @Test
    @DisplayName("Should intercept PreparedStatement and capture set* parameter operations accurately")
    void shouldInterceptPreparedStatementParameters() throws SQLException {
        String insertSql = "INSERT INTO test_record (id, name, nullable_col, amount) VALUES (?, ?, ?, ?)";

        try (Connection conn = proxyDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(insertSql)) {
            ps.setInt(1, 101);
            ps.setString(2, "ALPHA_PARTICIPANT");
            ps.setNull(3, Types.VARCHAR);
            ps.setBigDecimal(4, new BigDecimal("9876.543210"));
            ps.executeUpdate();
        }

        List<RecordedQuery> recorded = NPlusOneGateScope.getRecordedQueries();
        assertThat(recorded).hasSize(1);

        RecordedQuery insertQuery = recorded.get(0);
        assertThat(insertQuery.rawSql()).contains("INSERT INTO test_record");
        assertThat(insertQuery.parameters()).hasSize(4);

        // Verify parameter operations reflectively preserved method names and arguments
        List<ParameterSetOperation> params = insertQuery.parameters();
        assertThat(params.get(0).getMethod().getName()).isEqualTo("setInt");
        assertThat(params.get(0).getArgs()).containsExactly(1, 101);

        assertThat(params.get(1).getMethod().getName()).isEqualTo("setString");
        assertThat(params.get(1).getArgs()).containsExactly(2, "ALPHA_PARTICIPANT");

        assertThat(params.get(2).getMethod().getName()).isEqualTo("setNull");
        assertThat(params.get(2).getArgs()).containsExactly(3, Types.VARCHAR);

        assertThat(params.get(3).getMethod().getName()).isEqualTo("setBigDecimal");
        assertThat(params.get(3).getArgs()).containsExactly(4, new BigDecimal("9876.543210"));
    }

    @Test
    @DisplayName("Should capture SELECT queries with parameters and application stack frames")
    void shouldCaptureSelectQueriesWithStackFrames() throws SQLException {
        String selectSql = "SELECT name, amount FROM test_record WHERE id = ?";

        try (Connection conn = proxyDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(selectSql)) {
            ps.setInt(1, 101);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    // consume
                }
            }
        }

        List<RecordedQuery> recorded = NPlusOneGateScope.getRecordedQueries();
        assertThat(recorded).hasSize(1);

        RecordedQuery selectQuery = recorded.get(0);
        assertThat(selectQuery.rawSql()).isEqualTo(selectSql);
        assertThat(selectQuery.parameters()).hasSize(1);
        assertThat(selectQuery.parameters().get(0).getArgs()).containsExactly(1, 101);

        // Verify stack frames contain this test method
        assertThat(selectQuery.stackFrames())
                .anyMatch(frame -> frame.getMethodName().equals("shouldCaptureSelectQueriesWithStackFrames"));
    }

    @Test
    @DisplayName("Diagnostic mode should prevent proxy from recording EXPLAIN queries")
    void diagnosticModeShouldPreventRecording() throws SQLException {
        NPlusOneGateScope.withDiagnosticMode(() -> {
            try (Connection conn = proxyDataSource.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("SELECT 1");
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });

        assertThat(NPlusOneGateScope.getRecordedQueries()).isEmpty();
    }
}

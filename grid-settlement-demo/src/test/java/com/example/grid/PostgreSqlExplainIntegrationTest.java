package com.example.grid;

import com.example.grid.fixture.GridDataSeeder;
import com.gridsettlement.gate.annotation.EnableNPlusOneAiGate;
import com.gridsettlement.gate.explain.ExplainPlanRunner;
import com.gridsettlement.gate.interceptor.RecordedQuery;
import net.ttddyy.dsproxy.proxy.ParameterSetOperation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@EnableNPlusOneAiGate(threshold = 5, basePackage = "com.example.grid")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgreSqlExplainIntegrationTest extends PostgreSqlTestContainerBase {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private GridDataSeeder dataSeeder;

    private final ExplainPlanRunner explainRunner = new ExplainPlanRunner();

    @BeforeAll
    void setUpTestData() {
        dataSeeder.seedDeclarations(LocalDate.of(2026, 10, 1), 5);
    }

    @Test
    @DisplayName("Should execute safe EXPLAIN (FORMAT JSON) on PostgreSQL 16 and reveal Index Scan with seqscan=off")
    void shouldExecuteSafeExplainOnPostgreSql() throws Exception {
        Method setLongMethod = java.sql.PreparedStatement.class.getMethod("setLong", int.class, long.class);
        ParameterSetOperation op = new ParameterSetOperation(setLongMethod, new Object[]{1, 1L});

        RecordedQuery query = new RecordedQuery(
                "SELECT * FROM bilateral_contract WHERE id = ?",
                List.of(op),
                List.of(),
                false,
                Instant.now()
        );

        String planSummary = explainRunner.executeExplain(dataSource, query);

        // Verify that enable_seqscan=off forced an Index Scan on the primary key index
        assertThat(planSummary)
                .contains("Index Scan on bilateral_contract")
                .contains("bilateral_contract_pkey");
    }
}

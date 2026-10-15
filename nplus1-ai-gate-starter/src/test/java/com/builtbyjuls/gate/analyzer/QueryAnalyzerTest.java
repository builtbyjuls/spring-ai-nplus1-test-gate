package com.builtbyjuls.gate.analyzer;

import com.builtbyjuls.gate.interceptor.RecordedQuery;
import net.ttddyy.dsproxy.proxy.ParameterSetOperation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QueryAnalyzerTest {

    private final QueryAnalyzer analyzer = new QueryAnalyzer();

    @Test
    @DisplayName("Should flag repeated SELECT queries reaching or exceeding threshold")
    void shouldFlagRepeatedQueriesExceedingThreshold() {
        List<RecordedQuery> queries = new ArrayList<>();
        StackTraceElement callSite = new StackTraceElement("com.example.service.TradeService", "getTrades", "TradeService.java", 42);

        for (int i = 0; i < 6; i++) {
            queries.add(new RecordedQuery(
                    "SELECT * FROM bilateral_contract WHERE id = ?",
                    List.of(),
                    List.of(callSite),
                    false,
                    Instant.now()
            ));
        }

        List<QueryGroup> violations = analyzer.analyze(queries, 5, "com.example");

        assertThat(violations).hasSize(1);
        QueryGroup violation = violations.get(0);
        assertThat(violation.executionCount()).isEqualTo(6);
        assertThat(violation.normalizedSql()).isEqualTo("SELECT * FROM bilateral_contract WHERE id = ?");
        assertThat(violation.callSiteFrame()).isEqualTo("com.example.service.TradeService.getTrades(TradeService.java:42)");
    }

    @Test
    @DisplayName("Should ignore queries below the threshold")
    void shouldIgnoreQueriesBelowThreshold() {
        List<RecordedQuery> queries = new ArrayList<>();
        StackTraceElement callSite = new StackTraceElement("com.example.service.TradeService", "getTrades", "TradeService.java", 42);

        for (int i = 0; i < 4; i++) {
            queries.add(new RecordedQuery(
                    "SELECT * FROM bilateral_contract WHERE id = ?",
                    List.of(),
                    List.of(callSite),
                    false,
                    Instant.now()
            ));
        }

        List<QueryGroup> violations = analyzer.analyze(queries, 5, "com.example");
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Should drop sequence calls and non-SELECT queries")
    void shouldDropSequencesAndNonSelectQueries() {
        List<RecordedQuery> queries = new ArrayList<>();
        StackTraceElement callSite = new StackTraceElement("com.example.service.TradeService", "createTrade", "TradeService.java", 15);

        for (int i = 0; i < 10; i++) {
            queries.add(new RecordedQuery("select nextval('trade_seq')", List.of(), List.of(callSite), false, Instant.now()));
            queries.add(new RecordedQuery("INSERT INTO trade VALUES (?)", List.of(), List.of(callSite), false, Instant.now()));
        }

        List<QueryGroup> violations = analyzer.analyze(queries, 5, "com.example");
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Should drop Hibernate @BatchSize queries where batchFetchQueue is true")
    void shouldDropHibernateBatchSizeQueries() {
        List<RecordedQuery> queries = new ArrayList<>();
        StackTraceElement callSite = new StackTraceElement("com.example.service.TradeService", "getTrades", "TradeService.java", 42);

        for (int i = 0; i < 10; i++) {
            queries.add(new RecordedQuery(
                    "SELECT * FROM trade_item WHERE trade_id IN (?, ?, ?)",
                    List.of(),
                    List.of(callSite),
                    true, // batchFetchQueue active
                    Instant.now()
            ));
        }

        List<QueryGroup> violations = analyzer.analyze(queries, 5, "com.example");
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Should drop intentional pagination queries where offset strictly increases")
    void shouldDropIntentionalPaginationQueries() throws Exception {
        Method setIntMethod = java.sql.PreparedStatement.class.getMethod("setInt", int.class, int.class);
        String sql = "SELECT * FROM contract ORDER BY id LIMIT ? OFFSET ?";
        StackTraceElement callSite = new StackTraceElement("com.example.service.TradeService", "loadAllPages", "TradeService.java", 100);

        List<RecordedQuery> queries = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            ParameterSetOperation limitOp = new ParameterSetOperation(setIntMethod, new Object[]{1, 10});
            ParameterSetOperation offsetOp = new ParameterSetOperation(setIntMethod, new Object[]{2, i * 10});
            queries.add(new RecordedQuery(sql, List.of(limitOp, offsetOp), List.of(callSite), false, Instant.now()));
        }

        List<QueryGroup> violations = analyzer.analyze(queries, 5, "com.example");
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Should skip dynamic proxies and resolve the true service call site")
    void shouldSkipProxiesAndResolveServiceCallSite() {
        StackTraceElement proxyFrame = new StackTraceElement("com.example.domain.Contract$HibernateProxy", "getCode", null, -1);
        StackTraceElement cglibFrame = new StackTraceElement("com.example.service.TradeService$$SpringCGLIB$$0", "getTrades", null, -1);
        StackTraceElement serviceFrame = new StackTraceElement("com.example.service.TradeService", "getTrades", "TradeService.java", 88);

        StackTraceElement resolved = QueryAnalyzer.extractTopCallSite(List.of(proxyFrame, cglibFrame, serviceFrame), "com.example");
        assertThat(resolved).isEqualTo(serviceFrame);
    }
}

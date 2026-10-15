package com.gridsettlement.gate.listener;

import com.gridsettlement.gate.analyzer.QueryAnalyzer;
import com.gridsettlement.gate.analyzer.QueryGroup;
import com.gridsettlement.gate.annotation.EnableNPlusOneAiGate;
import com.gridsettlement.gate.interceptor.NPlusOneGateScope;
import com.gridsettlement.gate.interceptor.RecordedQuery;
import jakarta.persistence.EntityManager;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * Spring TestExecutionListener enforcing Hibernate N+1 query loop detection.
 * Ordered at 4100 so afterTestMethod executes before TransactionalTestExecutionListener (4000)
 * rolls back the test transaction.
 */
public class NPlusOneTestExecutionListener implements TestExecutionListener, Ordered {

    public static final int ORDER = 4100;

    private final QueryAnalyzer queryAnalyzer = new QueryAnalyzer();

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public void beforeTestMethod(TestContext testContext) {
        NPlusOneGateScope.reset();
    }

    @Override
    public void afterTestMethod(TestContext testContext) {
        // If the test has already failed, do not mask the root cause failure
        if (testContext.getTestException() != null) {
            return;
        }

        EnableNPlusOneAiGate gateConfig = AnnotatedElementUtils.findMergedAnnotation(
                testContext.getTestClass(), EnableNPlusOneAiGate.class
        );
        if (gateConfig == null) {
            return;
        }

        try {
            // Pre-analysis flush inside diagnostic mode so uncommitted rows exist in DB before analysis
            flushIfTransactionActive(testContext);

            List<RecordedQuery> queries = NPlusOneGateScope.getRecordedQueries();
            List<QueryGroup> violations = queryAnalyzer.analyze(queries, gateConfig.threshold(), gateConfig.basePackage());

            if (!violations.isEmpty()) {
                QueryGroup primaryViolation = violations.get(0);
                String failureReport = buildFailureReport(primaryViolation, gateConfig.threshold());
                throw new AssertionError(failureReport);
            }
        } finally {
            NPlusOneGateScope.reset();
        }
    }

    private void flushIfTransactionActive(TestContext testContext) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return;
        }

        try {
            ApplicationContext appContext = testContext.getApplicationContext();
            EntityManager entityManager = appContext.getBeanProvider(EntityManager.class).getIfAvailable();
            if (entityManager != null) {
                NPlusOneGateScope.withDiagnosticMode(() -> {
                    try {
                        entityManager.flush();
                    } catch (Exception ignored) {
                        // Suppress flush exceptions if transaction is marked rollback-only
                    }
                    return null;
                });
            }
        } catch (Exception ignored) {
            // Application context may not be available or does not contain JPA beans
        }
    }

    private String buildFailureReport(QueryGroup violation, int threshold) {
        return String.format(
                "%n[N+1 AI GATE FAILURE] Detected %d repeated SELECT queries (Threshold: %d)%n" +
                "  Child Loop   (N) : %s%n" +
                "  Offending SQL    : %s%n",
                violation.executionCount(),
                threshold,
                violation.callSiteFrame(),
                violation.normalizedSql()
        );
    }
}

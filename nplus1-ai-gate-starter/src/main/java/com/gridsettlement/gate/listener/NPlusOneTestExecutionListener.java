package com.gridsettlement.gate.listener;

import com.gridsettlement.gate.ai.NPlusOneAiDiagnosisService;
import com.gridsettlement.gate.ai.model.NPlusOneDiagnosis;
import com.gridsettlement.gate.analyzer.CorrelatedQueryContext;
import com.gridsettlement.gate.analyzer.ParentChildCorrelator;
import com.gridsettlement.gate.analyzer.QueryAnalyzer;
import com.gridsettlement.gate.analyzer.QueryGroup;
import com.gridsettlement.gate.annotation.EnableNPlusOneAiGate;
import com.gridsettlement.gate.cache.FingerprintCacheService;
import com.gridsettlement.gate.explain.ExplainPlanRunner;
import com.gridsettlement.gate.interceptor.NPlusOneGateScope;
import com.gridsettlement.gate.interceptor.RecordedQuery;
import jakarta.persistence.EntityManager;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.util.List;

/**
 * Spring TestExecutionListener enforcing Hibernate N+1 query loop detection.
 * Ordered at 4100 so afterTestMethod executes before TransactionalTestExecutionListener (4000)
 * rolls back the test transaction.
 */
public class NPlusOneTestExecutionListener implements TestExecutionListener, Ordered {

    public static final int ORDER = 4100;

    private final QueryAnalyzer queryAnalyzer;
    private final ParentChildCorrelator correlator;
    private final ExplainPlanRunner explainRunner;
    private final NPlusOneAiDiagnosisService diagnosisService;

    public NPlusOneTestExecutionListener() {
        this(new QueryAnalyzer(), new ParentChildCorrelator(), new ExplainPlanRunner(), new NPlusOneAiDiagnosisService(new FingerprintCacheService()));
    }

    public NPlusOneTestExecutionListener(
            QueryAnalyzer queryAnalyzer,
            ParentChildCorrelator correlator,
            ExplainPlanRunner explainRunner
    ) {
        this(queryAnalyzer, correlator, explainRunner, new NPlusOneAiDiagnosisService(new FingerprintCacheService()));
    }

    public NPlusOneTestExecutionListener(
            QueryAnalyzer queryAnalyzer,
            ParentChildCorrelator correlator,
            ExplainPlanRunner explainRunner,
            NPlusOneAiDiagnosisService diagnosisService
    ) {
        this.queryAnalyzer = queryAnalyzer;
        this.correlator = correlator;
        this.explainRunner = explainRunner;
        this.diagnosisService = diagnosisService;
    }

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
                DataSource dataSource = findDataSource(testContext);

                String explainPlan = NPlusOneGateScope.withDiagnosticMode(() ->
                        explainRunner.executeExplain(dataSource, primaryViolation.firstQuery())
                );

                CorrelatedQueryContext context = correlator.correlate(queries, primaryViolation, explainPlan);
                NPlusOneAiDiagnosisService aiService = resolveAiDiagnosisService(testContext);
                NPlusOneDiagnosis diagnosis = aiService.diagnose(context);
                String failureReport = buildFailureReport(context, gateConfig.threshold(), diagnosis);
                throw new AssertionError(failureReport);
            }
        } finally {
            NPlusOneGateScope.reset();
        }
    }

    private NPlusOneAiDiagnosisService resolveAiDiagnosisService(TestContext testContext) {
        try {
            ApplicationContext appContext = testContext.getApplicationContext();
            NPlusOneAiDiagnosisService bean = appContext.getBeanProvider(NPlusOneAiDiagnosisService.class).getIfAvailable();
            if (bean != null) {
                return bean;
            }
        } catch (Exception ignored) {
            // App context might not be available in mock tests
        }
        return this.diagnosisService != null ? this.diagnosisService : new NPlusOneAiDiagnosisService(new FingerprintCacheService());
    }

    private DataSource findDataSource(TestContext testContext) {
        try {
            ApplicationContext appContext = testContext.getApplicationContext();
            return appContext.getBeanProvider(DataSource.class).getIfAvailable();
        } catch (Exception ignored) {
            return null;
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

    private String buildFailureReport(CorrelatedQueryContext context, int threshold, NPlusOneDiagnosis diagnosis) {
        return String.format(
                "%n[N+1 AI GATE FAILURE] Detected %d repeated SELECT queries (Threshold: %d)%n" +
                "  Parent Query (1) : %s%n" +
                "  Child Loop   (N) : %s%n" +
                "  Offending SQL    : %s%n" +
                "  PG Explain Plan  : %s%n" +
                "  AI Diagnosis     : %s%n" +
                "  Recommended Fix  : %s%n",
                context.executionCount(),
                threshold,
                context.parentRepositorySignature(),
                context.childCallSite(),
                context.offendingSql(),
                context.explainPlan(),
                diagnosis != null ? diagnosis.diagnosis() : "N/A",
                diagnosis != null ? diagnosis.recommendedFix() : "N/A"
        );
    }
}

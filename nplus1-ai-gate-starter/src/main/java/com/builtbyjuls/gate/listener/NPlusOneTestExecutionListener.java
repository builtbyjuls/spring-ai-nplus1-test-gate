package com.builtbyjuls.gate.listener;

import com.builtbyjuls.gate.ai.NPlusOneAiDiagnosisService;
import com.builtbyjuls.gate.ai.model.NPlusOneDiagnosis;
import com.builtbyjuls.gate.analyzer.CorrelatedQueryContext;
import com.builtbyjuls.gate.analyzer.ParentChildCorrelator;
import com.builtbyjuls.gate.analyzer.QueryAnalyzer;
import com.builtbyjuls.gate.analyzer.QueryGroup;
import com.builtbyjuls.gate.annotation.EnableNPlusOneAiGate;
import com.builtbyjuls.gate.cache.FingerprintCacheService;
import com.builtbyjuls.gate.config.GateMode;
import com.builtbyjuls.gate.config.NPlusOneGateProperties;
import com.builtbyjuls.gate.explain.ExplainPlanRunner;
import com.builtbyjuls.gate.interceptor.NPlusOneGateScope;
import com.builtbyjuls.gate.interceptor.RecordedQuery;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
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

    private static final Logger log = LoggerFactory.getLogger(NPlusOneTestExecutionListener.class);

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
        try {
            // If the test has already failed, do not mask the root cause failure
            if (testContext.getTestException() != null) {
                return;
            }

            EnableNPlusOneAiGate gateConfig = AnnotatedElementUtils.findMergedAnnotation(
                    testContext.getTestClass(), EnableNPlusOneAiGate.class
            );
            if (!isGateEnabled(testContext, gateConfig)) {
                return;
            }

            // Pre-analysis flush inside diagnostic mode so uncommitted rows exist in DB before analysis
            flushIfTransactionActive(testContext);

            List<RecordedQuery> queries = NPlusOneGateScope.getRecordedQueries();
            if (queries.isEmpty()) {
                return;
            }

            int threshold = resolveThreshold(testContext, gateConfig);
            String basePackage = resolveBasePackage(testContext, gateConfig);
            List<QueryGroup> violations = queryAnalyzer.analyze(queries, threshold, basePackage);

            if (!violations.isEmpty()) {
                QueryGroup primaryViolation = violations.get(0);
                DataSource dataSource = findDataSource(testContext);

                String explainPlan = NPlusOneGateScope.withDiagnosticMode(() ->
                        explainRunner.executeExplain(dataSource, primaryViolation.firstQuery())
                );

                CorrelatedQueryContext context = correlator.correlate(queries, primaryViolation, explainPlan);
                NPlusOneAiDiagnosisService aiService = resolveAiDiagnosisService(testContext);
                NPlusOneDiagnosis diagnosis = aiService.diagnose(context);

                GateMode mode = resolveMode(testContext, gateConfig);
                String report = buildReport(context, threshold, diagnosis, mode);
                if (mode == GateMode.WARN) {
                    log.warn(report);
                } else {
                    throw new AssertionError(report);
                }
            }
        } finally {
            NPlusOneGateScope.reset();
        }
    }

    private boolean isGateEnabled(TestContext testContext, EnableNPlusOneAiGate gateConfig) {
        if (gateConfig != null && !gateConfig.enabled()) {
            return false;
        }
        try {
            ApplicationContext ctx = testContext.getApplicationContext();
            if (ctx != null) {
                var provider = ctx.getBeanProvider(NPlusOneGateProperties.class);
                if (provider != null) {
                    NPlusOneGateProperties properties = provider.getIfAvailable();
                    if (properties != null) {
                        return properties.isEnabled();
                    }
                }
                if (ctx.getEnvironment() != null) {
                    return ctx.getEnvironment().getProperty("nplus1.gate.enabled", Boolean.class, true);
                }
            }
        } catch (Exception ignored) {
            // App context might not be available or failed to load
        }
        return true;
    }

    private int resolveThreshold(TestContext testContext, EnableNPlusOneAiGate gateConfig) {
        if (gateConfig != null && gateConfig.threshold() > 0) {
            return gateConfig.threshold();
        }
        try {
            ApplicationContext ctx = testContext.getApplicationContext();
            if (ctx != null) {
                var provider = ctx.getBeanProvider(NPlusOneGateProperties.class);
                if (provider != null) {
                    NPlusOneGateProperties properties = provider.getIfAvailable();
                    if (properties != null && properties.getThreshold() > 0) {
                        return properties.getThreshold();
                    }
                }
                if (ctx.getEnvironment() != null) {
                    Integer prop = ctx.getEnvironment().getProperty("nplus1.gate.threshold", Integer.class);
                    if (prop != null && prop > 0) {
                        return prop;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return 5;
    }

    private String resolveBasePackage(TestContext testContext, EnableNPlusOneAiGate gateConfig) {
        if (gateConfig != null && !gateConfig.basePackage().isBlank()) {
            return gateConfig.basePackage();
        }
        try {
            ApplicationContext ctx = testContext.getApplicationContext();
            if (ctx != null) {
                var provider = ctx.getBeanProvider(NPlusOneGateProperties.class);
                if (provider != null) {
                    NPlusOneGateProperties properties = provider.getIfAvailable();
                    if (properties != null && properties.getBasePackage() != null && !properties.getBasePackage().isBlank()) {
                        return properties.getBasePackage();
                    }
                }
                if (ctx.getEnvironment() != null) {
                    String prop = ctx.getEnvironment().getProperty("nplus1.gate.base-package");
                    if (prop != null && !prop.isBlank()) {
                        return prop;
                    }
                }
                if (AutoConfigurationPackages.has(ctx)) {
                    List<String> pkgs = AutoConfigurationPackages.get(ctx);
                    if (!pkgs.isEmpty() && !pkgs.get(0).isBlank()) {
                        return pkgs.get(0);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return testContext.getTestClass() != null && testContext.getTestClass().getPackage() != null
                ? testContext.getTestClass().getPackageName()
                : "";
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

    private GateMode resolveMode(TestContext testContext, EnableNPlusOneAiGate gateConfig) {
        if (gateConfig != null && gateConfig.mode() != GateMode.DEFAULT) {
            return gateConfig.mode();
        }
        try {
            ApplicationContext ctx = testContext.getApplicationContext();
            if (ctx != null) {
                var provider = ctx.getBeanProvider(NPlusOneGateProperties.class);
                if (provider != null) {
                    NPlusOneGateProperties properties = provider.getIfAvailable();
                    if (properties != null && properties.getMode() != null) {
                        return properties.getMode();
                    }
                }
                if (ctx.getEnvironment() != null) {
                    String modeProp = ctx.getEnvironment().getProperty("nplus1.gate.mode");
                    if (modeProp != null && !modeProp.isBlank()) {
                        return GateMode.valueOf(modeProp.trim().toUpperCase());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return GateMode.FAIL;
    }

    private String buildReport(CorrelatedQueryContext context, int threshold, NPlusOneDiagnosis diagnosis, GateMode mode) {
        String banner = (mode == GateMode.WARN) ? "[N+1 AI GATE WARNING]" : "[N+1 AI GATE FAILURE]";
        return String.format(
                "%n%s Detected %d repeated SELECT queries (Threshold: %d)%n" +
                "  Parent Query (1) : %s%n" +
                "  Child Loop   (N) : %s%n" +
                "  Offending SQL    : %s%n" +
                "  PG Explain Plan  : %s%n" +
                "  AI Diagnosis     : %s%n" +
                "  Recommended Fix  : %s%n",
                banner,
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

    private String buildFailureReport(CorrelatedQueryContext context, int threshold, NPlusOneDiagnosis diagnosis) {
        return buildReport(context, threshold, diagnosis, GateMode.FAIL);
    }
}

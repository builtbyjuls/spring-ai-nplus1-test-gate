package com.builtbyjuls.gate.listener;

import com.builtbyjuls.gate.annotation.EnableNPlusOneAiGate;
import com.builtbyjuls.gate.config.GateMode;
import com.builtbyjuls.gate.interceptor.NPlusOneGateScope;
import com.builtbyjuls.gate.interceptor.RecordedQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestContext;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NPlusOneTestExecutionListenerTest {

    private final NPlusOneTestExecutionListener listener = new NPlusOneTestExecutionListener();

    @BeforeEach
    void setUp() {
        NPlusOneGateScope.reset();
    }

    @Test
    @DisplayName("Should declare order 4100 so it executes before TransactionalTestExecutionListener (4000) rollback")
    void shouldDeclareOrder4100() {
        assertThat(listener.getOrder()).isEqualTo(4100);
    }

    @Test
    @DisplayName("Should reset recorded queries before test method begins")
    void shouldResetScopeBeforeTestMethod() {
        NPlusOneGateScope.record(new RecordedQuery("SELECT 1", List.of(), List.of(), false, Instant.now()));
        assertThat(NPlusOneGateScope.getRecordedQueries()).hasSize(1);

        TestContext testContext = mock(TestContext.class);
        listener.beforeTestMethod(testContext);

        assertThat(NPlusOneGateScope.getRecordedQueries()).isEmpty();
    }

    @Test
    @DisplayName("Should fail test with AssertionError when recorded queries reach threshold")
    void shouldFailTestWhenThresholdExceeded() {
        TestContext testContext = mock(TestContext.class);
        when(testContext.getTestException()).thenReturn(null);
        doReturn(SampleTestClass.class).when(testContext).getTestClass();

        StackTraceElement frame = new StackTraceElement("com.example.service.OrderService", "getOrder", "OrderService.java", 25);
        for (int i = 0; i < 5; i++) {
            NPlusOneGateScope.record(new RecordedQuery(
                    "SELECT * FROM customer WHERE id = ?",
                    List.of(),
                    List.of(frame),
                    false,
                    Instant.now()
            ));
        }

        assertThatThrownBy(() -> listener.afterTestMethod(testContext))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("[N+1 AI GATE FAILURE]")
                .hasMessageContaining("Detected 5 repeated SELECT queries (Threshold: 5)")
                .hasMessageContaining("Parent Query (1) :")
                .hasMessageContaining("Child Loop   (N) : com.example.service.OrderService.getOrder(OrderService.java:25)")
                .hasMessageContaining("Offending SQL    : SELECT * FROM customer WHERE id = ?")
                .hasMessageContaining("PG Explain Plan  :")
                .hasMessageContaining("AI Diagnosis     :")
                .hasMessageContaining("Recommended Fix  :");

        // Scope should be reset after afterTestMethod
        assertThat(NPlusOneGateScope.getRecordedQueries()).isEmpty();
    }

    @Test
    @DisplayName("Should pass cleanly when query count is below threshold")
    void shouldPassCleanlyWhenBelowThreshold() {
        TestContext testContext = mock(TestContext.class);
        when(testContext.getTestException()).thenReturn(null);
        doReturn(SampleTestClass.class).when(testContext).getTestClass();

        StackTraceElement frame = new StackTraceElement("com.example.service.OrderService", "getOrder", "OrderService.java", 25);
        for (int i = 0; i < 3; i++) {
            NPlusOneGateScope.record(new RecordedQuery(
                    "SELECT * FROM customer WHERE id = ?",
                    List.of(),
                    List.of(frame),
                    false,
                    Instant.now()
            ));
        }

        listener.afterTestMethod(testContext);
        assertThat(NPlusOneGateScope.getRecordedQueries()).isEmpty();
    }

    @Test
    @DisplayName("Should not mask test failure if test already threw an exception")
    void shouldNotMaskExistingTestFailure() {
        TestContext testContext = mock(TestContext.class);
        when(testContext.getTestException()).thenReturn(new RuntimeException("Business error"));
        doReturn(SampleTestClass.class).when(testContext).getTestClass();

        StackTraceElement frame = new StackTraceElement("com.example.service.OrderService", "getOrder", "OrderService.java", 25);
        for (int i = 0; i < 10; i++) {
            NPlusOneGateScope.record(new RecordedQuery(
                    "SELECT * FROM customer WHERE id = ?",
                    List.of(),
                    List.of(frame),
                    false,
                    Instant.now()
            ));
        }

        // Listener should do nothing and not throw AssertionError
        listener.afterTestMethod(testContext);
    }

    @Test
    @DisplayName("Should be registered and invoked by Spring TestContextManager via @EnableNPlusOneAiGate")
    void shouldBeRegisteredByTestContextManager() {
        org.springframework.test.context.TestContextManager tcm = new org.springframework.test.context.TestContextManager(SampleTestClass.class);
        boolean foundListener = tcm.getTestExecutionListeners().stream()
                .anyMatch(l -> l instanceof NPlusOneTestExecutionListener);
        assertThat(foundListener).isTrue();
    }

    @Test
    @DisplayName("Should be registered by default in TestContextManager without any annotations")
    void shouldBeRegisteredByDefaultWithoutAnyAnnotation() {
        org.springframework.test.context.TestContextManager tcm = new org.springframework.test.context.TestContextManager(UnannotatedTestClass.class);
        boolean foundListener = tcm.getTestExecutionListeners().stream()
                .anyMatch(l -> l instanceof NPlusOneTestExecutionListener);
        assertThat(foundListener).isTrue();
    }

    @Test
    @DisplayName("Should fail test even without annotation when default threshold is exceeded")
    void shouldFailEvenWithoutAnnotationWhenThresholdExceeded() {
        TestContext testContext = mock(TestContext.class);
        when(testContext.getTestException()).thenReturn(null);
        doReturn(UnannotatedTestClass.class).when(testContext).getTestClass();

        StackTraceElement frame = new StackTraceElement("com.example.service.OrderService", "getOrder", "OrderService.java", 25);
        for (int i = 0; i < 5; i++) {
            NPlusOneGateScope.record(new RecordedQuery(
                    "SELECT * FROM customer WHERE id = ?",
                    List.of(),
                    List.of(frame),
                    false,
                    Instant.now()
            ));
        }

        assertThatThrownBy(() -> listener.afterTestMethod(testContext))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("[N+1 AI GATE FAILURE]")
                .hasMessageContaining("Detected 5 repeated SELECT queries (Threshold: 5)");
    }

    @Test
    @DisplayName("Should skip gate check when @EnableNPlusOneAiGate has enabled = false")
    void shouldSkipWhenAnnotationExplicitlyDisabled() {
        TestContext testContext = mock(TestContext.class);
        when(testContext.getTestException()).thenReturn(null);
        doReturn(DisabledAnnotatedTestClass.class).when(testContext).getTestClass();

        StackTraceElement frame = new StackTraceElement("com.example.service.OrderService", "getOrder", "OrderService.java", 25);
        for (int i = 0; i < 10; i++) {
            NPlusOneGateScope.record(new RecordedQuery(
                    "SELECT * FROM customer WHERE id = ?",
                    List.of(),
                    List.of(frame),
                    false,
                    Instant.now()
            ));
        }

        listener.afterTestMethod(testContext);
        assertThat(NPlusOneGateScope.getRecordedQueries()).isEmpty();
    }

    @Test
    @DisplayName("Should skip gate check when nplus1.gate.enabled property is false")
    void shouldSkipWhenPropertyDisabled() {
        TestContext testContext = mock(TestContext.class);
        org.springframework.context.ApplicationContext appContext = mock(org.springframework.context.ApplicationContext.class);
        org.springframework.core.env.Environment env = mock(org.springframework.core.env.Environment.class);
        when(testContext.getApplicationContext()).thenReturn(appContext);
        when(appContext.getEnvironment()).thenReturn(env);
        when(env.getProperty("nplus1.gate.enabled", Boolean.class, true)).thenReturn(false);
        when(testContext.getTestException()).thenReturn(null);
        doReturn(UnannotatedTestClass.class).when(testContext).getTestClass();

        StackTraceElement frame = new StackTraceElement("com.example.service.OrderService", "getOrder", "OrderService.java", 25);
        for (int i = 0; i < 10; i++) {
            NPlusOneGateScope.record(new RecordedQuery(
                    "SELECT * FROM customer WHERE id = ?",
                    List.of(),
                    List.of(frame),
                    false,
                    Instant.now()
            ));
        }

        listener.afterTestMethod(testContext);
    }

    @Test
    @DisplayName("Should respect threshold configured via nplus1.gate.threshold property")
    void shouldRespectThresholdFromProperties() {
        TestContext testContext = mock(TestContext.class);
        org.springframework.context.ApplicationContext appContext = mock(org.springframework.context.ApplicationContext.class);
        org.springframework.core.env.Environment env = mock(org.springframework.core.env.Environment.class);
        when(testContext.getApplicationContext()).thenReturn(appContext);
        when(appContext.getEnvironment()).thenReturn(env);
        when(env.getProperty("nplus1.gate.enabled", Boolean.class, true)).thenReturn(true);
        when(env.getProperty("nplus1.gate.threshold", Integer.class)).thenReturn(10);
        when(testContext.getTestException()).thenReturn(null);
        doReturn(UnannotatedTestClass.class).when(testContext).getTestClass();

        StackTraceElement frame = new StackTraceElement("com.example.service.OrderService", "getOrder", "OrderService.java", 25);
        for (int i = 0; i < 7; i++) {
            NPlusOneGateScope.record(new RecordedQuery(
                    "SELECT * FROM customer WHERE id = ?",
                    List.of(),
                    List.of(frame),
                    false,
                    Instant.now()
            ));
        }

        // 7 queries is below threshold 10, so it should pass cleanly
        listener.afterTestMethod(testContext);
        assertThat(NPlusOneGateScope.getRecordedQueries()).isEmpty();
    }

    @Test
    @DisplayName("Should log warning and not fail test when nplus1.gate.mode is WARN")
    void shouldLogWarningAndNotFailTestWhenModeIsWarn() {
        TestContext testContext = mock(TestContext.class);
        org.springframework.context.ApplicationContext appContext = mock(org.springframework.context.ApplicationContext.class);
        org.springframework.core.env.Environment env = mock(org.springframework.core.env.Environment.class);
        when(testContext.getApplicationContext()).thenReturn(appContext);
        when(appContext.getEnvironment()).thenReturn(env);
        when(env.getProperty("nplus1.gate.enabled", Boolean.class, true)).thenReturn(true);
        when(env.getProperty("nplus1.gate.mode")).thenReturn("WARN");
        when(testContext.getTestException()).thenReturn(null);
        doReturn(UnannotatedTestClass.class).when(testContext).getTestClass();

        StackTraceElement frame = new StackTraceElement("com.example.service.OrderService", "getOrder", "OrderService.java", 25);
        for (int i = 0; i < 5; i++) {
            NPlusOneGateScope.record(new RecordedQuery(
                    "SELECT * FROM customer WHERE id = ?",
                    List.of(),
                    List.of(frame),
                    false,
                    Instant.now()
            ));
        }

        // Mode is WARN: does not throw AssertionError, logs warning, and clears scope
        listener.afterTestMethod(testContext);
        assertThat(NPlusOneGateScope.getRecordedQueries()).isEmpty();
    }

    @Test
    @DisplayName("Should respect @EnableNPlusOneAiGate(mode = GateMode.WARN)")
    void shouldRespectAnnotationModeWarn() {
        TestContext testContext = mock(TestContext.class);
        when(testContext.getTestException()).thenReturn(null);
        doReturn(WarnAnnotatedTestClass.class).when(testContext).getTestClass();

        StackTraceElement frame = new StackTraceElement("com.example.service.OrderService", "getOrder", "OrderService.java", 25);
        for (int i = 0; i < 5; i++) {
            NPlusOneGateScope.record(new RecordedQuery(
                    "SELECT * FROM customer WHERE id = ?",
                    List.of(),
                    List.of(frame),
                    false,
                    Instant.now()
            ));
        }

        // Mode is WARN: passes cleanly without throwing AssertionError
        listener.afterTestMethod(testContext);
        assertThat(NPlusOneGateScope.getRecordedQueries()).isEmpty();
    }

    @Test
    @DisplayName("Should allow annotation mode FAIL to override global WARN mode")
    void shouldAllowAnnotationToOverrideGlobalWarnWithFail() {
        TestContext testContext = mock(TestContext.class);
        org.springframework.context.ApplicationContext appContext = mock(org.springframework.context.ApplicationContext.class);
        org.springframework.core.env.Environment env = mock(org.springframework.core.env.Environment.class);
        when(testContext.getApplicationContext()).thenReturn(appContext);
        when(appContext.getEnvironment()).thenReturn(env);
        when(env.getProperty("nplus1.gate.enabled", Boolean.class, true)).thenReturn(true);
        when(env.getProperty("nplus1.gate.mode")).thenReturn("WARN");
        when(testContext.getTestException()).thenReturn(null);
        doReturn(FailOverrideAnnotatedTestClass.class).when(testContext).getTestClass();

        StackTraceElement frame = new StackTraceElement("com.example.service.OrderService", "getOrder", "OrderService.java", 25);
        for (int i = 0; i < 5; i++) {
            NPlusOneGateScope.record(new RecordedQuery(
                    "SELECT * FROM customer WHERE id = ?",
                    List.of(),
                    List.of(frame),
                    false,
                    Instant.now()
            ));
        }

        // Annotation specifies FAIL: overrides global WARN property and throws AssertionError
        assertThatThrownBy(() -> listener.afterTestMethod(testContext))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("[N+1 AI GATE FAILURE]")
                .hasMessageContaining("Detected 5 repeated SELECT queries");
    }

    @Test
    @DisplayName("Should export reports when violation occurs and reporting is enabled")
    void shouldExportReportsWhenViolationOccurs(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        TestContext testContext = mock(TestContext.class);
        org.springframework.context.ApplicationContext appContext = mock(org.springframework.context.ApplicationContext.class);
        org.springframework.core.env.Environment env = mock(org.springframework.core.env.Environment.class);
        when(testContext.getApplicationContext()).thenReturn(appContext);
        when(appContext.getEnvironment()).thenReturn(env);
        when(env.getProperty("nplus1.gate.enabled", Boolean.class, true)).thenReturn(true);
        when(env.getProperty("nplus1.gate.report-enabled", Boolean.class, true)).thenReturn(true);
        when(env.getProperty("nplus1.gate.report-output-dir")).thenReturn(tempDir.toString());
        when(testContext.getTestException()).thenReturn(null);
        doReturn(SampleTestClass.class).when(testContext).getTestClass();
        java.lang.reflect.Method testMethod = SampleTestClass.class.getDeclaredMethod("sampleMethod");
        when(testContext.getTestMethod()).thenReturn(testMethod);

        StackTraceElement frame = new StackTraceElement("com.example.service.OrderService", "getOrder", "OrderService.java", 25);
        for (int i = 0; i < 5; i++) {
            NPlusOneGateScope.record(new RecordedQuery(
                    "SELECT * FROM customer WHERE id = ?",
                    List.of(),
                    List.of(frame),
                    false,
                    Instant.now()
            ));
        }

        assertThatThrownBy(() -> listener.afterTestMethod(testContext))
                .isInstanceOf(AssertionError.class);

        assertThat(java.nio.file.Files.exists(tempDir.resolve("report.json"))).isTrue();
        assertThat(java.nio.file.Files.exists(tempDir.resolve("summary.md"))).isTrue();
        assertThat(java.nio.file.Files.readString(tempDir.resolve("summary.md")))
                .contains("SampleTestClass")
                .contains("sampleMethod");
    }

    @Test
    @DisplayName("Should not export reports when report-enabled is false")
    void shouldNotExportReportsWhenDisabled(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) {
        TestContext testContext = mock(TestContext.class);
        org.springframework.context.ApplicationContext appContext = mock(org.springframework.context.ApplicationContext.class);
        org.springframework.core.env.Environment env = mock(org.springframework.core.env.Environment.class);
        when(testContext.getApplicationContext()).thenReturn(appContext);
        when(appContext.getEnvironment()).thenReturn(env);
        when(env.getProperty("nplus1.gate.enabled", Boolean.class, true)).thenReturn(true);
        when(env.getProperty("nplus1.gate.report-enabled", Boolean.class, true)).thenReturn(false);
        when(env.getProperty("nplus1.gate.report-output-dir")).thenReturn(tempDir.toString());
        when(testContext.getTestException()).thenReturn(null);
        doReturn(SampleTestClass.class).when(testContext).getTestClass();

        StackTraceElement frame = new StackTraceElement("com.example.service.OrderService", "getOrder", "OrderService.java", 25);
        for (int i = 0; i < 5; i++) {
            NPlusOneGateScope.record(new RecordedQuery(
                    "SELECT * FROM customer WHERE id = ?",
                    List.of(),
                    List.of(frame),
                    false,
                    Instant.now()
            ));
        }

        assertThatThrownBy(() -> listener.afterTestMethod(testContext))
                .isInstanceOf(AssertionError.class);

        assertThat(java.nio.file.Files.exists(tempDir.resolve("report.json"))).isFalse();
        assertThat(java.nio.file.Files.exists(tempDir.resolve("summary.md"))).isFalse();
    }

    @Test
    @DisplayName("Should suppress failure when test method is annotated with @IgnoreNPlusOne")
    void shouldSuppressFailureWhenMethodAnnotatedWithIgnoreNPlusOne() throws Exception {
        TestContext testContext = mock(TestContext.class);
        when(testContext.getTestException()).thenReturn(null);
        doReturn(MethodIgnoredTestClass.class).when(testContext).getTestClass();
        java.lang.reflect.Method testMethod = MethodIgnoredTestClass.class.getDeclaredMethod("exemptedMethod");
        when(testContext.getTestMethod()).thenReturn(testMethod);

        StackTraceElement frame = new StackTraceElement("com.example.service.OrderService", "getOrder", "OrderService.java", 25);
        for (int i = 0; i < 5; i++) {
            NPlusOneGateScope.record(new RecordedQuery(
                    "SELECT * FROM customer WHERE id = ?",
                    List.of(),
                    List.of(frame),
                    false,
                    Instant.now()
            ));
        }

        // Should not throw AssertionError due to @IgnoreNPlusOne
        listener.afterTestMethod(testContext);
        assertThat(NPlusOneGateScope.getRecordedQueries()).isEmpty();
    }

    @Test
    @DisplayName("Should suppress failure when test class is annotated with @IgnoreNPlusOne")
    void shouldSuppressFailureWhenClassAnnotatedWithIgnoreNPlusOne() throws Exception {
        TestContext testContext = mock(TestContext.class);
        when(testContext.getTestException()).thenReturn(null);
        doReturn(ClassIgnoredTestClass.class).when(testContext).getTestClass();
        java.lang.reflect.Method testMethod = ClassIgnoredTestClass.class.getDeclaredMethod("normalMethod");
        when(testContext.getTestMethod()).thenReturn(testMethod);

        StackTraceElement frame = new StackTraceElement("com.example.service.OrderService", "getOrder", "OrderService.java", 25);
        for (int i = 0; i < 5; i++) {
            NPlusOneGateScope.record(new RecordedQuery(
                    "SELECT * FROM customer WHERE id = ?",
                    List.of(),
                    List.of(frame),
                    false,
                    Instant.now()
            ));
        }

        // Should not throw AssertionError due to class-level @IgnoreNPlusOne
        listener.afterTestMethod(testContext);
        assertThat(NPlusOneGateScope.getRecordedQueries()).isEmpty();
    }

    @Test
    @DisplayName("Should suppress failure when violation matches baseline entry")
    void shouldSuppressFailureWhenMatchingBaselineEntry(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        java.nio.file.Path baselineFile = tempDir.resolve("nplus1-baseline.json");
        com.builtbyjuls.gate.baseline.NPlusOneBaselineService baselineService = new com.builtbyjuls.gate.baseline.NPlusOneBaselineService();
        baselineService.recordBaseline(new com.builtbyjuls.gate.baseline.NPlusOneBaselineEntry(
                SampleTestClass.class.getName(),
                "sampleMethod",
                "Unknown",
                "com.example.service.OrderService.getOrder(OrderService.java:25)",
                "fp-placeholder",
                java.time.Instant.now().toString()
        ), baselineFile.toString());

        TestContext testContext = mock(TestContext.class);
        org.springframework.context.ApplicationContext appContext = mock(org.springframework.context.ApplicationContext.class);
        org.springframework.core.env.Environment env = mock(org.springframework.core.env.Environment.class);
        when(testContext.getApplicationContext()).thenReturn(appContext);
        when(appContext.getEnvironment()).thenReturn(env);
        when(env.getProperty("nplus1.gate.enabled", Boolean.class, true)).thenReturn(true);
        when(env.getProperty("nplus1.gate.baseline-file")).thenReturn(baselineFile.toString());
        when(testContext.getTestException()).thenReturn(null);
        doReturn(SampleTestClass.class).when(testContext).getTestClass();
        java.lang.reflect.Method testMethod = SampleTestClass.class.getDeclaredMethod("sampleMethod");
        when(testContext.getTestMethod()).thenReturn(testMethod);

        StackTraceElement frame = new StackTraceElement("com.example.service.OrderService", "getOrder", "OrderService.java", 25);
        for (int i = 0; i < 5; i++) {
            NPlusOneGateScope.record(new RecordedQuery(
                    "SELECT * FROM customer WHERE id = ?",
                    List.of(),
                    List.of(frame),
                    false,
                    Instant.now()
            ));
        }

        // Matches baseline: does not throw AssertionError
        listener.afterTestMethod(testContext);
        assertThat(NPlusOneGateScope.getRecordedQueries()).isEmpty();
    }

    @Test
    @DisplayName("Should generate baseline entry when generate-baseline is true")
    void shouldGenerateBaselineEntryWhenGenerateBaselineIsTrue(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        java.nio.file.Path baselineFile = tempDir.resolve("generated-baseline.json");

        TestContext testContext = mock(TestContext.class);
        org.springframework.context.ApplicationContext appContext = mock(org.springframework.context.ApplicationContext.class);
        org.springframework.core.env.Environment env = mock(org.springframework.core.env.Environment.class);
        when(testContext.getApplicationContext()).thenReturn(appContext);
        when(appContext.getEnvironment()).thenReturn(env);
        when(env.getProperty("nplus1.gate.enabled", Boolean.class, true)).thenReturn(true);
        when(env.getProperty("nplus1.gate.generate-baseline", Boolean.class, false)).thenReturn(true);
        when(env.getProperty("nplus1.gate.baseline-file")).thenReturn(baselineFile.toString());
        when(testContext.getTestException()).thenReturn(null);
        doReturn(SampleTestClass.class).when(testContext).getTestClass();
        java.lang.reflect.Method testMethod = SampleTestClass.class.getDeclaredMethod("sampleMethod");
        when(testContext.getTestMethod()).thenReturn(testMethod);

        StackTraceElement frame = new StackTraceElement("com.example.service.OrderService", "getOrder", "OrderService.java", 25);
        for (int i = 0; i < 5; i++) {
            NPlusOneGateScope.record(new RecordedQuery(
                    "SELECT * FROM customer WHERE id = ?",
                    List.of(),
                    List.of(frame),
                    false,
                    Instant.now()
            ));
        }

        // Generating baseline suppresses assertion failure and writes baseline file
        listener.afterTestMethod(testContext);
        assertThat(java.nio.file.Files.exists(baselineFile)).isTrue();
        assertThat(java.nio.file.Files.readString(baselineFile))
                .contains("SampleTestClass")
                .contains("sampleMethod");
    }

    @EnableNPlusOneAiGate(threshold = 5, basePackage = "com.example")
    private static class MethodIgnoredTestClass {
        @com.builtbyjuls.gate.annotation.IgnoreNPlusOne(reason = "Known legacy batch method")
        void exemptedMethod() {
        }
    }

    @EnableNPlusOneAiGate(threshold = 5, basePackage = "com.example")
    @com.builtbyjuls.gate.annotation.IgnoreNPlusOne(reason = "Known legacy test class")
    private static class ClassIgnoredTestClass {
        void normalMethod() {
        }
    }

    @EnableNPlusOneAiGate(threshold = 5, basePackage = "com.example")
    private static class SampleTestClass {
        void sampleMethod() {
        }
    }

    private static class UnannotatedTestClass {
    }

    @EnableNPlusOneAiGate(enabled = false)
    private static class DisabledAnnotatedTestClass {
    }

    @EnableNPlusOneAiGate(mode = GateMode.WARN, threshold = 5, basePackage = "com.example")
    private static class WarnAnnotatedTestClass {
    }

    @EnableNPlusOneAiGate(mode = GateMode.FAIL, threshold = 5, basePackage = "com.example")
    private static class FailOverrideAnnotatedTestClass {
    }

    @SuppressWarnings("unchecked")
    private static <T> org.mockito.stubbing.Stubber doReturn(T value) {
        return org.mockito.Mockito.doReturn(value);
    }
}

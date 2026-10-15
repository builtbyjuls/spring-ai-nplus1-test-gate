package com.builtbyjuls.gate.listener;

import com.builtbyjuls.gate.annotation.EnableNPlusOneAiGate;
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

    @EnableNPlusOneAiGate(threshold = 5, basePackage = "com.example")
    private static class SampleTestClass {
    }

    @SuppressWarnings("unchecked")
    private static <T> org.mockito.stubbing.Stubber doReturn(T value) {
        return org.mockito.Mockito.doReturn(value);
    }
}

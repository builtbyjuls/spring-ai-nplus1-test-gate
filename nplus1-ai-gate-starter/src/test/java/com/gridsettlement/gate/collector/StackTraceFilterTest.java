package com.gridsettlement.gate.collector;

import com.gridsettlement.gate.collector.StackTraceFilter.StackInspectionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StackTraceFilterTest {

    @Test
    @DisplayName("Should capture current test method as application frame")
    void shouldCaptureCurrentTestMethodAsApplicationFrame() {
        StackInspectionResult result = StackTraceFilter.inspectStack();

        assertThat(result.batchFetchQueue()).isFalse();
        assertThat(result.userFrames())
                .isNotEmpty()
                .anyMatch(frame -> frame.getClassName().equals(getClass().getName())
                        && frame.getMethodName().equals("shouldCaptureCurrentTestMethodAsApplicationFrame"));
    }

    @Test
    @DisplayName("Should filter frames by matching basePackage")
    void shouldFilterFramesByMatchingBasePackage() {
        StackInspectionResult matched = StackTraceFilter.inspectStack("com.gridsettlement.gate");
        assertThat(matched.userFrames())
                .isNotEmpty()
                .allMatch(frame -> frame.getClassName().startsWith("com.gridsettlement.gate"));

        StackInspectionResult unmatched = StackTraceFilter.inspectStack("com.other.application");
        assertThat(unmatched.userFrames()).isEmpty();
    }

    @Test
    @DisplayName("isApplicationFrame should ignore framework and starter internal classes")
    void shouldIgnoreFrameworkAndStarterClasses() {
        // Framework prefixes
        assertThat(StackTraceFilter.isApplicationFrame("java.lang.String", "")).isFalse();
        assertThat(StackTraceFilter.isApplicationFrame("jakarta.persistence.EntityManager", "")).isFalse();
        assertThat(StackTraceFilter.isApplicationFrame("jakarta.transaction.Transactional", "")).isFalse();
        assertThat(StackTraceFilter.isApplicationFrame("org.hibernate.Session", "")).isFalse();
        assertThat(StackTraceFilter.isApplicationFrame("org.springframework.data.repository.Repository", "")).isFalse();
        assertThat(StackTraceFilter.isApplicationFrame("org.junit.jupiter.api.Test", "")).isFalse();
        assertThat(StackTraceFilter.isApplicationFrame("net.ttddyy.dsproxy.ProxyDataSource", "")).isFalse();

        // Starter internal classes
        assertThat(StackTraceFilter.isApplicationFrame("com.gridsettlement.gate.collector.StackTraceFilter", "")).isFalse();
        assertThat(StackTraceFilter.isApplicationFrame("com.gridsettlement.gate.interceptor.NPlusOneGateScope", "")).isFalse();
        assertThat(StackTraceFilter.isApplicationFrame("com.gridsettlement.gate.interceptor.NPlusOneQueryExecutionListener", "")).isFalse();
    }

    @Test
    @DisplayName("isApplicationFrame should accept application classes matching basePackage")
    void shouldAcceptApplicationClassesMatchingBasePackage() {
        String serviceClass = "com.example.grid.service.GridDailySettlementService";
        String repoClass = "com.example.grid.repository.ContractDeclarationRepository";

        // When basePackage is specified
        assertThat(StackTraceFilter.isApplicationFrame(serviceClass, "com.example.grid")).isTrue();
        assertThat(StackTraceFilter.isApplicationFrame(repoClass, "com.example.grid")).isTrue();
        assertThat(StackTraceFilter.isApplicationFrame("com.other.service.OtherService", "com.example.grid")).isFalse();

        // When basePackage is empty (matches any non-infrastructure class)
        assertThat(StackTraceFilter.isApplicationFrame(serviceClass, "")).isTrue();
        assertThat(StackTraceFilter.isApplicationFrame(serviceClass, null)).isTrue();
    }

    @Test
    @DisplayName("isBatchFetchMarker should detect BatchFetchQueue and BatchLoader markers")
    void shouldDetectBatchFetchMarkers() {
        assertThat(StackTraceFilter.isBatchFetchMarker("org.hibernate.engine.spi.BatchFetchQueue")).isTrue();
        assertThat(StackTraceFilter.isBatchFetchMarker("org.hibernate.loader.ast.internal.CollectionBatchLoaderArrayParam")).isTrue();
        assertThat(StackTraceFilter.isBatchFetchMarker("org.hibernate.loader.ast.internal.EntityBatchLoaderArrayParam")).isTrue();
        assertThat(StackTraceFilter.isBatchFetchMarker("org.hibernate.loader.ast.spi.BatchLoader")).isTrue();
        assertThat(StackTraceFilter.isBatchFetchMarker("org.hibernate.loader.ast.internal.SingleIdEntityLoaderStandardImpl")).isFalse();
        assertThat(StackTraceFilter.isBatchFetchMarker("com.example.grid.service.GridDailySettlementService")).isFalse();
    }
}

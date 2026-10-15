package com.example.grid;

import com.example.grid.domain.MeterReadingHeader;
import com.example.grid.fixture.GridDataSeeder;
import com.example.grid.repository.MeterReadingHeaderRepository;
import com.example.grid.service.GridDailySettlementService;
import com.gridsettlement.gate.ai.NPlusOneAiDiagnosisService;
import com.gridsettlement.gate.ai.model.NPlusOneDiagnosis;
import com.gridsettlement.gate.annotation.EnableNPlusOneAiGate;
import com.gridsettlement.gate.cache.FingerprintCacheKey;
import com.gridsettlement.gate.cache.FingerprintCacheService;
import com.gridsettlement.gate.interceptor.NPlusOneGateScope;
import com.gridsettlement.gate.listener.NPlusOneTestExecutionListener;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestContext;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@EnableNPlusOneAiGate(threshold = 5, basePackage = "com.example.grid")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NPlusOneAiDiagnosisIntegrationTest extends PostgreSqlTestContainerBase {

    private static final LocalDate TRADING_DATE = LocalDate.of(2026, 10, 20);

    @Autowired
    private GridDataSeeder dataSeeder;

    @Autowired
    private GridDailySettlementService settlementService;

    @Autowired
    private MeterReadingHeaderRepository meterHeaderRepository;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private FingerprintCacheService cacheService;

    @Autowired
    private NPlusOneAiDiagnosisService diagnosisService;

    private final NPlusOneTestExecutionListener listener = new NPlusOneTestExecutionListener();

    @BeforeAll
    void setUpTestData() {
        dataSeeder.seedDeclarations(TRADING_DATE, GridDataSeeder.DEFAULT_CONTRACT_COUNT);
        dataSeeder.seedMeterReadings(TRADING_DATE, GridDataSeeder.DEFAULT_CONTRACT_COUNT);
    }

    @BeforeEach
    void resetScope() {
        NPlusOneGateScope.reset();
    }

    @Test
    @Transactional
    @DisplayName("Scenario A: Interception produces structured failure report with AI Diagnosis and Recommended Fix")
    void scenarioA_shouldProduceStructuredAiDiagnosisAndPopulateCache() {
        settlementService.buildParticipantSummary(TRADING_DATE);

        TestContext mockTestContext = mock(TestContext.class);
        when(mockTestContext.getApplicationContext()).thenReturn(applicationContext);
        when(mockTestContext.getTestException()).thenReturn(null);
        doReturn(SampleScenarioATest.class).when(mockTestContext).getTestClass();

        assertThatThrownBy(() -> listener.afterTestMethod(mockTestContext))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("[N+1 AI GATE FAILURE]")
                .hasMessageContaining("Detected 20 repeated SELECT queries (Threshold: 5)")
                .hasMessageContaining("Parent Query (1) : ContractDeclarationRepository.findConfirmedForParticipantSummary(LocalDate)")
                .hasMessageContaining("Child Loop   (N) : com.example.grid.service.GridDailySettlementService.buildParticipantSummary")
                .hasMessageContaining("Offending SQL    :")
                .hasMessageContaining("PG Explain Plan  :")
                .hasMessageContaining("AI Diagnosis     : Lazy @ManyToOne traversal inside loop.")
                .hasMessageContaining("Recommended Fix  : Add @EntityGraph");

        // Verify SHA-256 fingerprint entry was saved in cache
        assertThat(cacheService.size()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @Transactional
    @DisplayName("Scenario B: Interception produces JPQL DTO aggregation recommendation for lazy collection loop")
    void scenarioB_shouldProduceJpqlDtoAggregationRecommendation() {
        settlementService.buildDailyVolumeAudit(TRADING_DATE);

        TestContext mockTestContext = mock(TestContext.class);
        when(mockTestContext.getApplicationContext()).thenReturn(applicationContext);
        when(mockTestContext.getTestException()).thenReturn(null);
        doReturn(SampleScenarioBTest.class).when(mockTestContext).getTestClass();

        assertThatThrownBy(() -> listener.afterTestMethod(mockTestContext))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("[N+1 AI GATE FAILURE]")
                .hasMessageContaining("Detected 20 repeated SELECT queries (Threshold: 5)")
                .hasMessageContaining("Parent Query (1) : ContractDeclarationRepository.findConfirmedForDailyVolumeAudit(LocalDate)")
                .hasMessageContaining("Child Loop   (N) : com.example.grid.service.GridDailySettlementService.buildDailyVolumeAudit")
                .hasMessageContaining("Offending SQL    :")
                .hasMessageContaining("PG Explain Plan  :")
                .hasMessageContaining("AI Diagnosis     : Lazy collection traversal inside loop.")
                .hasMessageContaining("Recommended Fix  : Replace collection loading with a JPQL DTO aggregation query");
    }

    @Test
    @DisplayName("Should retrieve cached diagnosis directly using SHA-256 fingerprint key")
    void shouldRetrieveCachedDiagnosisUsingFingerprintKey() {
        String key = FingerprintCacheKey.compute(
                "select bc.id from bilateral_contract bc where bc.id = ?",
                "com.example.grid.service.GridDailySettlementService.buildParticipantSummary:54",
                "ContractDeclarationRepository.findByTradingDate(LocalDate)",
                "bilateral_contract"
        );

        NPlusOneDiagnosis diagnosis = new NPlusOneDiagnosis(
                "Lazy @ManyToOne traversal inside loop.",
                "Add @EntityGraph(attributePaths = {\"bilateralContract\"}) to ContractDeclarationRepository.findByTradingDate(LocalDate).",
                "ENTITY_GRAPH"
        );

        cacheService.put(key, diagnosis);

        Optional<NPlusOneDiagnosis> retrieved = cacheService.get(key);
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().diagnosis()).isEqualTo("Lazy @ManyToOne traversal inside loop.");
        assertThat(retrieved.get().fixType()).isEqualTo("ENTITY_GRAPH");
    }

    @Test
    @Transactional
    @DisplayName("Scenario C: Refactored queries and @BatchSize pass test gate without violations or LLM calls")
    void scenarioC_shouldPassGateWithoutViolationsOrLlmCalls() {
        // Run optimized methods and @BatchSize loop
        settlementService.buildParticipantSummaryOptimized(TRADING_DATE);
        settlementService.buildDailyVolumeAuditOptimized(TRADING_DATE);

        List<MeterReadingHeader> headers = meterHeaderRepository.findByReadingDate(TRADING_DATE);
        for (MeterReadingHeader header : headers) {
            assertThat(header.getReadings()).isNotEmpty();
        }

        int cacheSizeBefore = cacheService.size();

        TestContext mockTestContext = mock(TestContext.class);
        when(mockTestContext.getApplicationContext()).thenReturn(applicationContext);
        when(mockTestContext.getTestException()).thenReturn(null);
        doReturn(SampleScenarioCTest.class).when(mockTestContext).getTestClass();

        // Must NOT throw AssertionError and must NOT invoke AI diagnosis
        listener.afterTestMethod(mockTestContext);

        assertThat(cacheService.size()).isEqualTo(cacheSizeBefore);
    }

    @EnableNPlusOneAiGate(threshold = 5, basePackage = "com.example.grid")
    private static class SampleScenarioATest {
    }

    @EnableNPlusOneAiGate(threshold = 5, basePackage = "com.example.grid")
    private static class SampleScenarioBTest {
    }

    @EnableNPlusOneAiGate(threshold = 5, basePackage = "com.example.grid")
    private static class SampleScenarioCTest {
    }
}

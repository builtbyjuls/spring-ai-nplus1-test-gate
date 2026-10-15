package com.builtbyjuls.grid;

import com.builtbyjuls.gate.cache.FingerprintCacheService;
import com.builtbyjuls.gate.interceptor.NPlusOneGateScope;
import com.builtbyjuls.gate.listener.NPlusOneTestExecutionListener;
import com.builtbyjuls.grid.fixture.GridDataSeeder;
import com.builtbyjuls.grid.service.GridDailySettlementService;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration test verifying WARN adoption mode: executes full diagnostic pipeline
 * and populates cache without throwing AssertionError or failing the test.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "nplus1.gate.mode=WARN")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WarnModeIntegrationTest extends PostgreSqlTestContainerBase {

    private static final LocalDate TRADING_DATE = LocalDate.of(2026, 10, 26);

    @Autowired
    private GridDataSeeder dataSeeder;

    @Autowired
    private GridDailySettlementService settlementService;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private FingerprintCacheService cacheService;

    private final NPlusOneTestExecutionListener listener = new NPlusOneTestExecutionListener();

    @BeforeAll
    void seedData() {
        dataSeeder.seedDeclarations(TRADING_DATE, GridDataSeeder.DEFAULT_CONTRACT_COUNT);
        dataSeeder.seedMeterReadings(TRADING_DATE, GridDataSeeder.DEFAULT_CONTRACT_COUNT);
    }

    @BeforeEach
    void resetScope() {
        NPlusOneGateScope.reset();
    }

    @Test
    @Transactional
    @DisplayName("Should execute full diagnostic pipeline in WARN mode without throwing AssertionError")
    void shouldLogWarningAndNotFailTestInWarnMode() {
        // Trigger N+1 un-optimized query loop
        settlementService.buildParticipantSummary(TRADING_DATE);

        assertThat(NPlusOneGateScope.getRecordedQueries()).isNotEmpty();

        TestContext mockContext = mock(TestContext.class);
        when(mockContext.getTestException()).thenReturn(null);
        doReturn(this.getClass()).when(mockContext).getTestClass();
        when(mockContext.getApplicationContext()).thenReturn(applicationContext);

        // Verification: afterTestMethod completes normally without throwing AssertionError
        assertThatCode(() -> listener.afterTestMethod(mockContext))
                .doesNotThrowAnyException();

        // Scope should be cleanly reset
        assertThat(NPlusOneGateScope.getRecordedQueries()).isEmpty();

        // Diagnostic pipeline ran and populated cache entry
        assertThat(cacheService.size()).isGreaterThanOrEqualTo(1);
    }
}

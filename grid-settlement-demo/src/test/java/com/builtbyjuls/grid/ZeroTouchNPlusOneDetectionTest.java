package com.builtbyjuls.grid;

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
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies zero-touch N+1 query loop detection without any @EnableNPlusOneAiGate annotation on the test class.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ZeroTouchNPlusOneDetectionTest extends PostgreSqlTestContainerBase {

    private static final LocalDate TRADING_DATE = LocalDate.of(2026, 10, 25);

    @Autowired
    private GridDataSeeder dataSeeder;

    @Autowired
    private GridDailySettlementService settlementService;

    @Autowired
    private ApplicationContext applicationContext;

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
    @DisplayName("Should automatically catch N+1 query loops without any @EnableNPlusOneAiGate annotation")
    void shouldCatchNPlusOneWithoutAnnotation() {
        // Trigger N+1 un-optimized query loop
        settlementService.buildParticipantSummary(TRADING_DATE);

        assertThat(NPlusOneGateScope.getRecordedQueries()).isNotEmpty();

        TestContext mockContext = mock(TestContext.class);
        when(mockContext.getTestException()).thenReturn(null);
        doReturn(this.getClass()).when(mockContext).getTestClass();
        when(mockContext.getApplicationContext()).thenReturn(applicationContext);

        assertThatThrownBy(() -> listener.afterTestMethod(mockContext))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("[N+1 AI GATE FAILURE]")
                .hasMessageContaining("Detected")
                .hasMessageContaining("repeated SELECT queries");
    }
}

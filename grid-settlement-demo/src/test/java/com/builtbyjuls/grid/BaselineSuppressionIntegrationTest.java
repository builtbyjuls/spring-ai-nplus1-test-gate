package com.builtbyjuls.grid;

import com.builtbyjuls.gate.annotation.IgnoreNPlusOne;
import com.builtbyjuls.gate.baseline.NPlusOneBaselineEntry;
import com.builtbyjuls.gate.baseline.NPlusOneBaselineService;
import com.builtbyjuls.gate.interceptor.NPlusOneGateScope;
import com.builtbyjuls.gate.listener.NPlusOneTestExecutionListener;
import com.builtbyjuls.gate.report.NPlusOneFinding;
import com.builtbyjuls.gate.report.NPlusOneReportExporter;
import com.builtbyjuls.grid.fixture.GridDataSeeder;
import com.builtbyjuls.grid.service.GridDailySettlementService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestContext;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration test verifying @IgnoreNPlusOne exemption and baseline suppression
 * against PostgreSQL 16 Testcontainers.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BaselineSuppressionIntegrationTest extends PostgreSqlTestContainerBase {

    private static final LocalDate TRADING_DATE = LocalDate.of(2026, 10, 26);

    @Autowired
    private GridDataSeeder dataSeeder;

    @Autowired
    private GridDailySettlementService settlementService;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private NPlusOneReportExporter reportExporter;

    private final NPlusOneTestExecutionListener listener = new NPlusOneTestExecutionListener();

    @BeforeAll
    void seedData() {
        dataSeeder.seedDeclarations(TRADING_DATE, GridDataSeeder.DEFAULT_CONTRACT_COUNT);
    }

    @BeforeEach
    void resetScope() {
        NPlusOneGateScope.reset();
        reportExporter.reset();
    }

    @Test
    @Transactional
    @IgnoreNPlusOne(reason = "Known legacy participant summary to be refactored")
    @DisplayName("Should suppress failure when test method is annotated with @IgnoreNPlusOne")
    void shouldSuppressFailureWithIgnoreAnnotation() throws Exception {
        settlementService.buildParticipantSummary(TRADING_DATE);
        assertThat(NPlusOneGateScope.getRecordedQueries()).isNotEmpty();

        TestContext mockContext = mock(TestContext.class);
        when(mockContext.getTestException()).thenReturn(null);
        doReturn(this.getClass()).when(mockContext).getTestClass();
        doReturn(this.getClass().getDeclaredMethod("shouldSuppressFailureWithIgnoreAnnotation")).when(mockContext).getTestMethod();
        when(mockContext.getApplicationContext()).thenReturn(applicationContext);

        assertThatCode(() -> listener.afterTestMethod(mockContext))
                .doesNotThrowAnyException();

        List<NPlusOneFinding> findings = reportExporter.getFindings();
        assertThat(findings).isNotEmpty();
        assertThat(findings.get(0).baselineSuppressed()).isTrue();
        assertThat(findings.get(0).suppressionReason()).contains("@IgnoreNPlusOne");
    }

    @Test
    @Transactional
    @DisplayName("Should suppress failure when violation matches baseline entry in nplus1-baseline.json")
    void shouldSuppressFailureWhenMatchingBaseline(@TempDir Path tempDir) throws Exception {
        Path baselinePath = tempDir.resolve("nplus1-baseline.json");
        NPlusOneBaselineService baselineService = new NPlusOneBaselineService();

        NPlusOneBaselineEntry entry = new NPlusOneBaselineEntry(
                this.getClass().getName(),
                "shouldSuppressFailureWhenMatchingBaseline",
                "ContractDeclarationRepository.findConfirmedForParticipantSummary(LocalDate)",
                "com.builtbyjuls.grid.service.GridDailySettlementService.buildParticipantSummary(GridDailySettlementService.java:46)",
                "placeholder-fp",
                Instant.now().toString()
        );
        baselineService.recordBaseline(entry, baselinePath.toString());

        settlementService.buildParticipantSummary(TRADING_DATE);
        assertThat(NPlusOneGateScope.getRecordedQueries()).isNotEmpty();

        TestContext mockContext = mock(TestContext.class);
        ApplicationContext mockAppContext = mock(ApplicationContext.class);
        Environment mockEnv = mock(Environment.class);
        when(mockContext.getApplicationContext()).thenReturn(mockAppContext);
        when(mockAppContext.getEnvironment()).thenReturn(mockEnv);
        when(mockAppContext.getBeanProvider(org.springframework.ai.chat.model.ChatModel.class))
                .thenReturn(applicationContext.getBeanProvider(org.springframework.ai.chat.model.ChatModel.class));
        when(mockAppContext.getBeanProvider(javax.sql.DataSource.class))
                .thenReturn(applicationContext.getBeanProvider(javax.sql.DataSource.class));
        when(mockAppContext.getBeanProvider(NPlusOneReportExporter.class))
                .thenReturn(applicationContext.getBeanProvider(NPlusOneReportExporter.class));
        when(mockEnv.getProperty("nplus1.gate.enabled", Boolean.class, true)).thenReturn(true);
        when(mockEnv.getProperty("nplus1.gate.baseline-file")).thenReturn(baselinePath.toString());
        when(mockContext.getTestException()).thenReturn(null);
        doReturn(this.getClass()).when(mockContext).getTestClass();
        doReturn(this.getClass().getDeclaredMethod("shouldSuppressFailureWhenMatchingBaseline", Path.class)).when(mockContext).getTestMethod();

        assertThatCode(() -> listener.afterTestMethod(mockContext))
                .doesNotThrowAnyException();

        List<NPlusOneFinding> findings = reportExporter.getFindings();
        assertThat(findings).isNotEmpty();
        assertThat(findings.get(0).baselineSuppressed()).isTrue();
    }
}

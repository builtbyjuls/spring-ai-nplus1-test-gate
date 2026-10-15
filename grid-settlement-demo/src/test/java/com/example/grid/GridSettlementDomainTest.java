package com.example.grid;

import com.example.grid.domain.ContractDeclaration;
import com.example.grid.domain.DeclarationStatus;
import com.example.grid.domain.IntervalQuantity;
import com.example.grid.domain.MeterReadingHeader;
import com.example.grid.fixture.GridDataSeeder;
import com.example.grid.repository.ContractDeclarationRepository;
import com.example.grid.repository.DailyVolumeAuditDto;
import com.example.grid.repository.MeterReadingHeaderRepository;
import com.example.grid.repository.ParticipantSummaryDto;
import com.example.grid.service.GridDailySettlementService;
import com.gridsettlement.gate.analyzer.QueryAnalyzer;
import com.gridsettlement.gate.analyzer.QueryGroup;
import com.gridsettlement.gate.annotation.EnableNPlusOneAiGate;
import com.gridsettlement.gate.interceptor.NPlusOneGateScope;
import com.gridsettlement.gate.interceptor.RecordedQuery;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@EnableNPlusOneAiGate(threshold = 5, basePackage = "com.example.grid")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GridSettlementDomainTest extends PostgreSqlTestContainerBase {

    private static final LocalDate TRADING_DATE = LocalDate.of(2026, 10, 15);

    @Autowired
    private GridDataSeeder dataSeeder;

    @Autowired
    private GridDailySettlementService settlementService;

    @Autowired
    private ContractDeclarationRepository declarationRepository;

    @Autowired
    private MeterReadingHeaderRepository meterHeaderRepository;

    @BeforeAll
    void setUpTestData() {
        dataSeeder.seedDeclarations(TRADING_DATE, GridDataSeeder.DEFAULT_CONTRACT_COUNT);
        dataSeeder.seedMeterReadings(TRADING_DATE, GridDataSeeder.DEFAULT_CONTRACT_COUNT);
    }

    @BeforeEach
    void setUp() {
        NPlusOneGateScope.reset();
    }

    @Test
    @Transactional
    @DisplayName("Should verify PostgreSQL 16 schema, data volume, and interval unique constraint")
    void shouldVerifyPostgreSqlSchemaAndConstraints() {
        List<ContractDeclaration> declarations = declarationRepository.findConfirmedForParticipantSummary(TRADING_DATE);
        assertThat(declarations).hasSize(GridDataSeeder.DEFAULT_CONTRACT_COUNT);

        ContractDeclaration first = declarations.get(0);
        assertThat(first.getIntervals()).hasSize(GridDataSeeder.INTERVALS_PER_DAY);

        // Verify composite unique constraint on (declaration_id, dispatch_interval, grid_node_code)
        IntervalQuantity duplicate = new IntervalQuantity(
                1,
                first.getIntervals().get(0).getGridNodeCode(),
                BigDecimal.TEN,
                first
        );

        first.addInterval(duplicate);
        assertThatThrownBy(() -> declarationRepository.saveAndFlush(first))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    @DisplayName("Scenario A: Unoptimized buildParticipantSummary triggers 41 queries vs. 1 query for optimized")
    void scenarioA_shouldDemonstrateUnoptimizedVsOptimizedParticipantSummary() {
        // Act Unoptimized: 1 parent SELECT + 20 bilateral_contract SELECTs + 20 buyer SELECTs
        NPlusOneGateScope.reset();
        List<ParticipantSummaryDto> unoptimized = settlementService.buildParticipantSummary(TRADING_DATE);

        assertThat(unoptimized).hasSize(GridDataSeeder.DEFAULT_CONTRACT_COUNT);
        List<RecordedQuery> unoptimizedQueries = NPlusOneGateScope.getRecordedQueries();

        // 1 parent + 20 child contracts + 20 child buyers = 41 queries
        assertThat(unoptimizedQueries).hasSize(41);

        // Act Optimized: @EntityGraph loads all associations in 1 single SELECT with JOINs
        NPlusOneGateScope.reset();
        List<ParticipantSummaryDto> optimized = settlementService.buildParticipantSummaryOptimized(TRADING_DATE);

        assertThat(optimized).hasSize(GridDataSeeder.DEFAULT_CONTRACT_COUNT);
        List<RecordedQuery> optimizedQueries = NPlusOneGateScope.getRecordedQueries();

        // Exactly 1 single query executed
        assertThat(optimizedQueries).hasSize(1);
    }

    @Test
    @Transactional
    @DisplayName("Scenario B: Unoptimized buildDailyVolumeAudit triggers 21 queries vs. 1 query for JPQL DTO aggregation")
    void scenarioB_shouldDemonstrateUnoptimizedVsOptimizedDailyVolumeAudit() {
        // Act Unoptimized: 1 parent SELECT + 20 child SELECTs loading 5,760 intervals
        NPlusOneGateScope.reset();
        List<DailyVolumeAuditDto> unoptimized = settlementService.buildDailyVolumeAudit(TRADING_DATE);

        assertThat(unoptimized).hasSize(GridDataSeeder.DEFAULT_CONTRACT_COUNT);
        assertThat(unoptimized.get(0).intervalCount()).isEqualTo(GridDataSeeder.INTERVALS_PER_DAY);
        List<RecordedQuery> unoptimizedQueries = NPlusOneGateScope.getRecordedQueries();

        // 1 parent query + 20 child collection queries
        assertThat(unoptimizedQueries).hasSize(21);

        // Act Optimized: Database executes single JPQL aggregation with GROUP BY
        NPlusOneGateScope.reset();
        List<DailyVolumeAuditDto> optimized = settlementService.buildDailyVolumeAuditOptimized(TRADING_DATE);

        assertThat(optimized).hasSize(GridDataSeeder.DEFAULT_CONTRACT_COUNT);
        assertThat(optimized.get(0).intervalCount()).isEqualTo(GridDataSeeder.INTERVALS_PER_DAY);
        List<RecordedQuery> optimizedQueries = NPlusOneGateScope.getRecordedQueries();

        // Exactly 1 single query executed
        assertThat(optimizedQueries).hasSize(1);
    }

    @Test
    @Transactional
    @DisplayName("Scenario C: Optimized baselines and @BatchSize pass the AI test gate cleanly")
    void scenarioC_shouldDemonstrateOptimizedBaselineAndBatchSizePassGateCleanly() {
        // 1. Optimized Scenario A executes exactly 1 query
        NPlusOneGateScope.reset();
        List<ParticipantSummaryDto> summary = settlementService.buildParticipantSummaryOptimized(TRADING_DATE);
        assertThat(summary).hasSize(GridDataSeeder.DEFAULT_CONTRACT_COUNT);
        assertThat(NPlusOneGateScope.getRecordedQueries()).hasSize(1);

        // 2. Optimized Scenario B executes exactly 1 query
        NPlusOneGateScope.reset();
        List<DailyVolumeAuditDto> audit = settlementService.buildDailyVolumeAuditOptimized(TRADING_DATE);
        assertThat(audit).hasSize(GridDataSeeder.DEFAULT_CONTRACT_COUNT);
        assertThat(NPlusOneGateScope.getRecordedQueries()).hasSize(1);

        // 3. BatchSize(size = 20) collection fetch executes queries with batchFetchQueue == true
        NPlusOneGateScope.reset();
        List<MeterReadingHeader> headers = meterHeaderRepository.findByReadingDate(TRADING_DATE);
        assertThat(headers).hasSize(GridDataSeeder.DEFAULT_CONTRACT_COUNT);

        int totalReadings = 0;
        for (MeterReadingHeader header : headers) {
            totalReadings += header.getReadings().size();
        }
        assertThat(totalReadings).isEqualTo(GridDataSeeder.DEFAULT_CONTRACT_COUNT * 5);

        List<RecordedQuery> batchQueries = NPlusOneGateScope.getRecordedQueries();
        assertThat(batchQueries).isNotEmpty();

        // Verify that BatchFetchQueue was detected in the collection loading query stack
        boolean hasBatchFetch = batchQueries.stream().anyMatch(RecordedQuery::batchFetchQueue);
        assertThat(hasBatchFetch).isTrue();

        // Verify that QueryAnalyzer drops all batchFetchQueue queries (0 violations even at threshold 1)
        QueryAnalyzer analyzer = new QueryAnalyzer();
        List<QueryGroup> violations = analyzer.analyze(batchQueries, 1, "com.example.grid");
        assertThat(violations).isEmpty();
    }
}

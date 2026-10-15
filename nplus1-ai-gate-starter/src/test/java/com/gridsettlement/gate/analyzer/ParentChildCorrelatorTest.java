package com.gridsettlement.gate.analyzer;

import com.gridsettlement.gate.interceptor.RecordedQuery;
import com.gridsettlement.gate.source.SourceLookupService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ParentChildCorrelatorTest {

    @Test
    @DisplayName("Should scan backward to find parent query and extract repository signature")
    void shouldFindParentQueryAndExtractSignature() {
        StackTraceElement parentCaller = new StackTraceElement("com.example.service.TradeService", "getTrades", "TradeService.java", 40);
        StackTraceElement childCaller = new StackTraceElement("com.example.service.TradeService", "getTrades", "TradeService.java", 55);

        RecordedQuery parentQuery = new RecordedQuery(
                "SELECT * FROM contract_declaration cd WHERE cd.trading_date = ?",
                List.of(),
                List.of(parentCaller),
                false,
                Instant.now()
        );

        RecordedQuery child1 = new RecordedQuery(
                "SELECT * FROM bilateral_contract WHERE id = ?",
                List.of(),
                List.of(childCaller),
                false,
                Instant.now()
        );
        RecordedQuery child2 = new RecordedQuery(
                "SELECT * FROM bilateral_contract WHERE id = ?",
                List.of(),
                List.of(childCaller),
                false,
                Instant.now()
        );

        List<RecordedQuery> allQueries = List.of(parentQuery, child1, child2);
        QueryGroup childGroup = new QueryGroup(
                "SELECT * FROM bilateral_contract WHERE id = ?",
                "com.example.service.TradeService.getTrades(TradeService.java:55)",
                childCaller,
                List.of(child1, child2)
        );

        SourceLookupService mockLookup = new SourceLookupService() {
            @Override
            public String findRepositoryMethodSignature(StackTraceElement serviceFrame) {
                return "ContractDeclarationRepository.findConfirmedForParticipantSummary(LocalDate)";
            }
        };

        ParentChildCorrelator correlator = new ParentChildCorrelator(mockLookup);
        CorrelatedQueryContext context = correlator.correlate(allQueries, childGroup, "Index Scan on bilateral_contract");

        assertThat(context.parentQuery()).isEqualTo(parentQuery);
        assertThat(context.parentRepositorySignature()).isEqualTo("ContractDeclarationRepository.findConfirmedForParticipantSummary(LocalDate)");
        assertThat(context.childCallSite()).isEqualTo("com.example.service.TradeService.getTrades(TradeService.java:55)");
        assertThat(context.offendingSql()).isEqualTo("SELECT * FROM bilateral_contract WHERE id = ?");
        assertThat(context.explainPlan()).isEqualTo("Index Scan on bilateral_contract");
    }

    @Test
    @DisplayName("Should handle standalone repeated loop without preceding parent query gracefully")
    void shouldHandleStandaloneLoopWithoutParentQuery() {
        StackTraceElement childCaller = new StackTraceElement("com.example.service.TradeService", "standaloneLoop", "TradeService.java", 20);
        RecordedQuery child1 = new RecordedQuery("SELECT 1", List.of(), List.of(childCaller), false, Instant.now());
        RecordedQuery child2 = new RecordedQuery("SELECT 1", List.of(), List.of(childCaller), false, Instant.now());

        QueryGroup childGroup = new QueryGroup("SELECT 1", "callSite", childCaller, List.of(child1, child2));

        ParentChildCorrelator correlator = new ParentChildCorrelator();
        CorrelatedQueryContext context = correlator.correlate(List.of(child1, child2), childGroup, "Seq Scan");

        assertThat(context.parentQuery()).isNull();
        assertThat(context.parentRepositorySignature()).isEqualTo("Unknown");
    }
}

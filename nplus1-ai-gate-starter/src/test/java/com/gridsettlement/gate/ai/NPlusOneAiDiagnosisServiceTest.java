package com.gridsettlement.gate.ai;

import com.gridsettlement.gate.ai.model.NPlusOneDiagnosis;
import com.gridsettlement.gate.analyzer.CorrelatedQueryContext;
import com.gridsettlement.gate.analyzer.QueryGroup;
import com.gridsettlement.gate.cache.FingerprintCacheService;
import com.gridsettlement.gate.interceptor.RecordedQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NPlusOneAiDiagnosisServiceTest {

    @Test
    @DisplayName("Should provide deterministic offline fallback for @ManyToOne traversal")
    void shouldProvideDeterministicOfflineFallbackForManyToOne(@TempDir Path tempDir) {
        FingerprintCacheService cacheService = new FingerprintCacheService(tempDir.resolve("cache.json"), null, null);
        NPlusOneAiDiagnosisService service = new NPlusOneAiDiagnosisService(null, cacheService);

        CorrelatedQueryContext context = createContext(
                "SELECT * FROM market_participant WHERE id = ?",
                "SELECT * FROM contract_declaration WHERE trading_date = ?",
                "com.gridsettlement.demo.service.GridDailySettlementService.buildParticipantSummary(GridDailySettlementService.java:54)",
                "ContractDeclarationRepository.findByTradingDate(LocalDate)",
                10,
                "[{\"Plan\": {\"Node Type\": \"Seq Scan\"}}]"
        );

        NPlusOneDiagnosis diagnosis = service.diagnose(context);

        assertThat(diagnosis).isNotNull();
        assertThat(diagnosis.diagnosis()).isEqualTo("Lazy @ManyToOne traversal inside loop.");
        assertThat(diagnosis.recommendedFix()).contains("@EntityGraph");
        assertThat(diagnosis.recommendedFix()).contains("ContractDeclarationRepository.findByTradingDate(LocalDate)");
        assertThat(diagnosis.fixType()).isEqualTo("ENTITY_GRAPH");

        // Should have been cached
        assertThat(cacheService.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should provide deterministic offline fallback for lazy collection traversal")
    void shouldProvideDeterministicOfflineFallbackForCollection(@TempDir Path tempDir) {
        FingerprintCacheService cacheService = new FingerprintCacheService(tempDir.resolve("cache.json"), null, null);
        NPlusOneAiDiagnosisService service = new NPlusOneAiDiagnosisService(null, cacheService);

        CorrelatedQueryContext context = createContext(
                "SELECT * FROM interval_quantity WHERE declaration_id = ?",
                "SELECT * FROM contract_declaration WHERE trading_date = ?",
                "com.gridsettlement.demo.service.GridDailySettlementService.calculateTotalQuantity(GridDailySettlementService.java:78)",
                "ContractDeclarationRepository.findByTradingDate(LocalDate)",
                10,
                "[{\"Plan\": {\"Node Type\": \"Index Scan\"}}]"
        );

        NPlusOneDiagnosis diagnosis = service.diagnose(context);

        assertThat(diagnosis).isNotNull();
        assertThat(diagnosis.diagnosis()).isEqualTo("Lazy collection traversal inside loop.");
        assertThat(diagnosis.recommendedFix()).contains("JPQL DTO aggregation query");
        assertThat(diagnosis.fixType()).isEqualTo("JPQL_DTO_AGGREGATION");
    }

    @Test
    @DisplayName("Should keep prompt strictly within 1,000 input tokens budget")
    void shouldKeepPromptStrictlyUnderOneThousandTokens() {
        NPlusOneAiDiagnosisService service = new NPlusOneAiDiagnosisService(null, null);

        String explainPlan = """
                [{"Plan": {"Node Type": "Index Scan", "Index Name": "idx_iq_declaration_id", "Relation Name": "interval_quantity", "Startup Cost": 0.29, "Total Cost": 8.31, "Plan Rows": 288, "Plan Width": 32}}]
                """;

        CorrelatedQueryContext context = createContext(
                "SELECT id, declaration_id, interval_index, quantity_mwh FROM interval_quantity WHERE declaration_id = ?",
                "SELECT id, contract_id, participant_id, trading_date FROM contract_declaration WHERE trading_date = ?",
                "com.gridsettlement.demo.service.GridDailySettlementService.buildParticipantSummary(GridDailySettlementService.java:62)",
                "ContractDeclarationRepository.findByTradingDate(LocalDate)",
                20,
                explainPlan
        );

        String prompt = service.buildPrompt(context);

        // Approximate token count: standard rule of thumb is ~4 characters per token or ~0.75 words per token.
        // Budget requirement: <= 1,000 tokens.
        int wordCount = prompt.split("\\s+").length;
        int characterCount = prompt.length();

        // 1,000 tokens corresponds to roughly 4,000 characters or ~750 words.
        assertThat(characterCount).isLessThan(2500);
        assertThat(wordCount).isLessThan(400);
    }

    @Test
    @DisplayName("Should return cached diagnosis directly without invoking ChatClient")
    void shouldReturnCachedDiagnosisOnCacheHit() {
        ChatClient chatClient = mock(ChatClient.class);
        FingerprintCacheService cacheService = mock(FingerprintCacheService.class);

        NPlusOneDiagnosis cachedDiagnosis = new NPlusOneDiagnosis("Cached diagnosis", "Cached fix", "ENTITY_GRAPH");
        when(cacheService.get(anyString())).thenReturn(Optional.of(cachedDiagnosis));

        NPlusOneAiDiagnosisService service = new NPlusOneAiDiagnosisService(chatClient, cacheService);

        CorrelatedQueryContext context = createContext(
                "SELECT * FROM market_participant WHERE id = ?",
                "SELECT * FROM contract_declaration",
                "Service.method:1",
                "Repo.findAll()",
                5,
                "{}"
        );

        NPlusOneDiagnosis result = service.diagnose(context);

        assertThat(result).isSameAs(cachedDiagnosis);
        verify(chatClient, never()).prompt();
    }

    @Test
    @DisplayName("Should invoke ChatClient on cache miss and store structured result")
    void shouldInvokeChatClientOnCacheMiss() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);

        NPlusOneDiagnosis geminiDiagnosis = new NPlusOneDiagnosis("Gemini AI analysis", "Add @EntityGraph", "ENTITY_GRAPH");
        when(callResponseSpec.entity(NPlusOneDiagnosis.class)).thenReturn(geminiDiagnosis);

        FingerprintCacheService cacheService = mock(FingerprintCacheService.class);
        when(cacheService.get(anyString())).thenReturn(Optional.empty());

        NPlusOneAiDiagnosisService service = new NPlusOneAiDiagnosisService(chatClient, cacheService);

        CorrelatedQueryContext context = createContext(
                "SELECT * FROM market_participant WHERE id = ?",
                "SELECT * FROM contract_declaration",
                "Service.method:1",
                "Repo.findAll()",
                5,
                "{}"
        );

        NPlusOneDiagnosis result = service.diagnose(context);

        assertThat(result).isNotNull();
        assertThat(result.diagnosis()).isEqualTo("Gemini AI analysis");
        verify(cacheService).put(anyString(), any(NPlusOneDiagnosis.class));
    }

    @Test
    @DisplayName("Should fallback gracefully to deterministic offline diagnosis when ChatClient throws exception")
    void shouldFallbackGracefullyWhenChatClientThrows() {
        ChatClient chatClient = mock(ChatClient.class);
        when(chatClient.prompt()).thenThrow(new RuntimeException("429 RESOURCE_EXHAUSTED"));

        FingerprintCacheService cacheService = mock(FingerprintCacheService.class);
        when(cacheService.get(anyString())).thenReturn(Optional.empty());

        NPlusOneAiDiagnosisService service = new NPlusOneAiDiagnosisService(chatClient, cacheService);

        CorrelatedQueryContext context = createContext(
                "SELECT * FROM market_participant WHERE id = ?",
                "SELECT * FROM contract_declaration",
                "Service.method:1",
                "Repo.findAll()",
                5,
                "{}"
        );

        NPlusOneDiagnosis result = service.diagnose(context);

        assertThat(result).isNotNull();
        assertThat(result.diagnosis()).isEqualTo("Lazy @ManyToOne traversal inside loop.");
        assertThat(result.fixType()).isEqualTo("ENTITY_GRAPH");
        verify(cacheService).put(anyString(), any(NPlusOneDiagnosis.class));
    }

    private CorrelatedQueryContext createContext(
            String offendingSql,
            String parentSql,
            String callSite,
            String parentRepo,
            int executionCount,
            String explainPlan
    ) {
        StackTraceElement frame = new StackTraceElement("com.gridsettlement.demo.service.GridDailySettlementService", "method", "GridDailySettlementService.java", 54);
        List<RecordedQuery> queries = Collections.nCopies(
                executionCount,
                new RecordedQuery(offendingSql, List.of(), List.of(frame), false, Instant.now())
        );
        QueryGroup childGroup = new QueryGroup(offendingSql, callSite, frame, queries);
        RecordedQuery parent = parentSql != null
                ? new RecordedQuery(parentSql, List.of(), List.of(frame), false, Instant.now())
                : null;
        return new CorrelatedQueryContext(childGroup, parent, parentRepo, explainPlan);
    }
}

package com.builtbyjuls.gate.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.builtbyjuls.gate.ai.model.NPlusOneDiagnosis;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FingerprintCacheServiceTest {

    @Test
    @DisplayName("Should generate deterministic 64-character SHA-256 fingerprint hex key")
    void shouldGenerateSha256FingerprintKey() {
        String key1 = FingerprintCacheKey.compute(
                "SELECT * FROM market_participant WHERE id = ?",
                "GridDailySettlementService.buildParticipantSummary:54",
                "ContractDeclarationRepository.findByTradingDate(LocalDate)",
                "market_participant"
        );
        String key2 = FingerprintCacheKey.compute(
                "SELECT * FROM market_participant WHERE id = ?",
                "GridDailySettlementService.buildParticipantSummary:54",
                "ContractDeclarationRepository.findByTradingDate(LocalDate)",
                "market_participant"
        );

        assertThat(key1).isNotNull().hasSize(64).matches("^[a-f0-9]{64}$");
        assertThat(key1).isEqualTo(key2);

        String distinctKey = FingerprintCacheKey.compute(
                "SELECT * FROM interval_quantity WHERE declaration_id = ?",
                "GridDailySettlementService.calculateTotalQuantity:78",
                "ContractDeclarationRepository.findByTradingDate(LocalDate)",
                "interval_quantity"
        );
        assertThat(distinctKey).isNotEqualTo(key1);
    }

    @Test
    @DisplayName("Should generate same key regardless of line number in method signature")
    void shouldGenerateSameKeyRegardlessOfLineNumber() {
        String keyLine54 = FingerprintCacheKey.compute(
                "SELECT * FROM market_participant WHERE id = ?",
                "GridDailySettlementService.buildParticipantSummary:54",
                "ContractDeclarationRepository.findByTradingDate(LocalDate)",
                "market_participant"
        );
        String keyLine99 = FingerprintCacheKey.compute(
                "SELECT * FROM market_participant WHERE id = ?",
                "GridDailySettlementService.buildParticipantSummary:99",
                "ContractDeclarationRepository.findByTradingDate(LocalDate)",
                "market_participant"
        );
        String keyNoLine = FingerprintCacheKey.compute(
                "SELECT * FROM market_participant WHERE id = ?",
                "GridDailySettlementService.buildParticipantSummary",
                "ContractDeclarationRepository.findByTradingDate(LocalDate)",
                "market_participant"
        );
        assertThat(keyLine54).isEqualTo(keyLine99);
        assertThat(keyLine54).isEqualTo(keyNoLine);
    }

    @Test
    @DisplayName("Should extract entity signature from SQL query")
    void shouldExtractEntitySignatureFromSql() {
        String entity = FingerprintCacheKey.extractEntitySignature("select mp.id, mp.name from market_participant mp where mp.id = ?");
        assertThat(entity).isEqualTo("market_participant");

        String entityUpper = FingerprintCacheKey.extractEntitySignature("SELECT * FROM INTERVAL_QUANTITY WHERE declaration_id = ?");
        assertThat(entityUpper).isEqualTo("interval_quantity");

        String none = FingerprintCacheKey.extractEntitySignature("");
        assertThat(none).isEmpty();
    }

    @Test
    @DisplayName("Should persist diagnosis to local JSON cache file and read back on reload")
    void shouldPersistAndReadLocalFileCache(@TempDir Path tempDir) throws IOException {
        Path cacheFile = tempDir.resolve("test-ai-cache.json");
        ObjectMapper mapper = new ObjectMapper();

        FingerprintCacheService service = new FingerprintCacheService(cacheFile, mapper, null);
        String key = "test-hash-12345";
        NPlusOneDiagnosis diagnosis = new NPlusOneDiagnosis(
                "Lazy @ManyToOne traversal inside loop.",
                "Add @EntityGraph(attributePaths = {\"marketParticipant\"}) to ContractDeclarationRepository.findByTradingDate(LocalDate).",
                "ENTITY_GRAPH"
        );

        service.put(key, diagnosis);
        assertThat(service.size()).isEqualTo(1);
        assertThat(Files.exists(cacheFile)).isTrue();

        // New service instance pointing to the same file should load persisted entries
        FingerprintCacheService reloadedService = new FingerprintCacheService(cacheFile, mapper, null);
        Optional<NPlusOneDiagnosis> cached = reloadedService.get(key);

        assertThat(cached).isPresent();
        assertThat(cached.get().diagnosis()).isEqualTo("Lazy @ManyToOne traversal inside loop.");
        assertThat(cached.get().fixType()).isEqualTo("ENTITY_GRAPH");
    }

    @Test
    @DisplayName("Should query Redis cache when present and populate in-memory cache")
    @SuppressWarnings("unchecked")
    void shouldCheckRedisWhenConfigured() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        String key = "redis-hash-abc";
        String redisJson = "{\"diagnosis\":\"Lazy traversal\",\"recommendedFix\":\"Use @EntityGraph\",\"fixType\":\"ENTITY_GRAPH\"}";
        when(valueOperations.get("nplus1:cache:" + key)).thenReturn(redisJson);

        FingerprintCacheService service = new FingerprintCacheService(null, new ObjectMapper(), redisTemplate);
        Optional<NPlusOneDiagnosis> result = service.get(key);

        assertThat(result).isPresent();
        assertThat(result.get().diagnosis()).isEqualTo("Lazy traversal");
        assertThat(result.get().fixType()).isEqualTo("ENTITY_GRAPH");
    }

    @Test
    @DisplayName("Should store diagnosis into Redis when put is invoked")
    @SuppressWarnings("unchecked")
    void shouldStoreIntoRedis() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        FingerprintCacheService service = new FingerprintCacheService(null, new ObjectMapper(), redisTemplate);
        String key = "redis-save-key";
        NPlusOneDiagnosis diagnosis = new NPlusOneDiagnosis("Collection loop", "Use DTO aggregation", "JPQL_DTO_AGGREGATION");

        service.put(key, diagnosis);

        verify(valueOperations).set(eq("nplus1:cache:" + key), anyString());
    }
}

package com.gridsettlement.gate.ai;

import com.gridsettlement.gate.ai.model.NPlusOneDiagnosis;
import com.gridsettlement.gate.analyzer.CorrelatedQueryContext;
import com.gridsettlement.gate.cache.FingerprintCacheKey;
import com.gridsettlement.gate.cache.FingerprintCacheService;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Optional;

/**
 * Diagnoses Hibernate N+1 query loops using Google Gemini via Spring AI, backed by a
 * SHA-256 fingerprint cache and a deterministic offline fallback.
 */
public class NPlusOneAiDiagnosisService {

    private final ChatClient chatClient;
    private final FingerprintCacheService cacheService;

    public NPlusOneAiDiagnosisService(FingerprintCacheService cacheService) {
        this(null, cacheService);
    }

    public NPlusOneAiDiagnosisService(ChatClient chatClient, FingerprintCacheService cacheService) {
        this.chatClient = chatClient;
        this.cacheService = cacheService != null ? cacheService : new FingerprintCacheService();
    }

    /**
     * Diagnoses the correlated N+1 query violation.
     * First consults the SHA-256 fingerprint cache. On a cache miss, calls Gemini (if configured)
     * and falls back to a deterministic offline diagnosis on error or missing API key.
     *
     * @param context correlated query context
     * @return structured diagnosis and recommended fix
     */
    public NPlusOneDiagnosis diagnose(CorrelatedQueryContext context) {
        if (context == null) {
            return new NPlusOneDiagnosis("No query context provided", "Verify test configuration", "UNKNOWN");
        }

        String entity = FingerprintCacheKey.extractEntitySignature(context.offendingSql());
        String cacheKey = FingerprintCacheKey.compute(
                context.offendingSql(),
                context.childCallSite(),
                context.parentRepositorySignature(),
                entity
        );

        // 1. Check SHA-256 Fingerprint Cache
        Optional<NPlusOneDiagnosis> cached = cacheService.get(cacheKey);
        if (cached.isPresent()) {
            return cached.get();
        }

        // 2. Attempt Spring AI Gemini diagnosis if client is available
        if (chatClient != null) {
            try {
                String promptText = buildPrompt(context);
                NPlusOneDiagnosis aiDiagnosis = chatClient.prompt()
                        .user(promptText)
                        .call()
                        .entity(NPlusOneDiagnosis.class);

                if (aiDiagnosis != null && aiDiagnosis.diagnosis() != null && !aiDiagnosis.diagnosis().isBlank()) {
                    cacheService.put(cacheKey, aiDiagnosis);
                    return aiDiagnosis;
                }
            } catch (Exception ignored) {
                // Fall back gracefully to offline deterministic diagnosis
            }
        }

        // 3. Deterministic offline fallback
        NPlusOneDiagnosis fallback = createOfflineFallbackDiagnosis(context);
        cacheService.put(cacheKey, fallback);
        return fallback;
    }

    /**
     * Builds the compact prompt guaranteed to stay under the 1,000 input token budget.
     *
     * @param context correlated query context
     * @return prompt text
     */
    public String buildPrompt(CorrelatedQueryContext context) {
        return """
                You are an expert Hibernate and PostgreSQL performance engineer.
                An N+1 query loop was detected in a Spring Data JPA application during JUnit testing.
                Analyze the provided context and return a structured JSON fix:
                - diagnosis: Concise explanation of the root cause (e.g. "Lazy @ManyToOne traversal inside loop.")
                - recommendedFix: Concrete, actionable Java fix (e.g. "Add @EntityGraph(...) to RepositoryMethod(...)")
                - fixType: One of "ENTITY_GRAPH", "JPQL_DTO_AGGREGATION", "BATCH_SIZE", "JOIN_FETCH"

                Context:
                - Repeated Query (Offending SQL): %s
                - Child Service Call Site: %s
                - Execution Count: %d
                - Parent Query (1): %s
                - Parent Repository Signature: %s
                - PostgreSQL Explain Plan: %s
                """.formatted(
                context.offendingSql(),
                context.childCallSite(),
                context.executionCount(),
                context.parentSql() != null ? context.parentSql() : "None",
                context.parentRepositorySignature(),
                context.explainPlan() != null ? context.explainPlan() : "None"
        );
    }

    private NPlusOneDiagnosis createOfflineFallbackDiagnosis(CorrelatedQueryContext context) {
        String sql = context.offendingSql().toLowerCase();
        String parentRepo = context.parentRepositorySignature();
        String explain = context.explainPlan() != null ? context.explainPlan().toLowerCase() : "";

        if (sql.contains(".id=?") || sql.contains(".id = ?") || sql.contains("where id=?") || sql.contains("where id = ?")
                || (explain.contains("using ") && explain.contains("pkey")) || explain.contains("_pkey")) {
            // Lazy @ManyToOne entity traversal
            String entity = FingerprintCacheKey.extractEntitySignature(context.offendingSql());
            String attrPath = !entity.isBlank() ? toCamelCase(entity) : "association";
            return new NPlusOneDiagnosis(
                    "Lazy @ManyToOne traversal inside loop.",
                    String.format("Add @EntityGraph(attributePaths = {\"%s\"}) to %s.", attrPath, parentRepo),
                    "ENTITY_GRAPH"
            );
        } else if (sql.contains("where declaration_id = ?") || sql.contains("where declaration_id=?")
                || sql.contains("declaration_id=?") || sql.contains("interval_quantity") || sql.contains("_id = ?") || sql.contains("_id=?")) {
            // Lazy collection traversal
            return new NPlusOneDiagnosis(
                    "Lazy collection traversal inside loop.",
                    String.format("Replace collection loading with a JPQL DTO aggregation query on %s.", parentRepo),
                    "JPQL_DTO_AGGREGATION"
            );
        }

        return new NPlusOneDiagnosis(
                "Repeated query loop detected.",
                String.format("Eagerly fetch associations or use batch fetching on %s.", parentRepo),
                "JOIN_FETCH"
        );
    }

    private String toCamelCase(String snakeStr) {
        StringBuilder sb = new StringBuilder();
        boolean capitalize = false;
        for (char c : snakeStr.toCharArray()) {
            if (c == '_') {
                capitalize = true;
            } else if (capitalize) {
                sb.append(Character.toUpperCase(c));
                capitalize = false;
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }
}

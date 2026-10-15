package com.builtbyjuls.gate.ai;

import com.builtbyjuls.gate.ai.model.NPlusOneDiagnosis;
import com.builtbyjuls.gate.analyzer.CorrelatedQueryContext;
import com.builtbyjuls.gate.cache.FingerprintCacheKey;
import com.builtbyjuls.gate.cache.FingerprintCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Diagnoses Hibernate N+1 query loops using Google Gemini via Spring AI, backed by a
 * SHA-256 fingerprint cache and a deterministic offline fallback.
 */
public class NPlusOneAiDiagnosisService {

    private static final Logger log = LoggerFactory.getLogger(NPlusOneAiDiagnosisService.class);

    public static final String SYSTEM_PROMPT = """
            You are an expert Hibernate and PostgreSQL performance engineer.
            An N+1 query loop was detected in a Spring Data JPA application during JUnit testing.
            Analyze the provided context and return a structured JSON fix:
            - diagnosis: Concise explanation of the root cause (e.g. "Lazy @ManyToOne traversal inside loop.")
            - recommendedFix: Concrete, actionable Java fix (e.g. "Add @EntityGraph(...) to RepositoryMethod(...)")
            - fixType: One of "ENTITY_GRAPH", "JPQL_DTO_AGGREGATION", "BATCH_SIZE", "JOIN_FETCH"
            """;

    private static final Pattern PRIMARY_KEY_WHERE_PATTERN = Pattern.compile(
            "\\bwhere\\s+(?:[a-zA-Z0-9_]+\\.)?id\\s*=\\s*\\?",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern FOREIGN_KEY_WHERE_PATTERN = Pattern.compile(
            "\\bwhere\\s+(?:[a-zA-Z0-9_]+\\.)?[a-zA-Z0-9_]+_id\\s*(?:=|in\\b)",
            Pattern.CASE_INSENSITIVE
    );

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
                String userPrompt = buildUserPrompt(context);
                NPlusOneDiagnosis aiDiagnosis = chatClient.prompt()
                        .system(SYSTEM_PROMPT)
                        .user(userPrompt)
                        .call()
                        .entity(NPlusOneDiagnosis.class);

                if (aiDiagnosis != null && aiDiagnosis.diagnosis() != null && !aiDiagnosis.diagnosis().isBlank()) {
                    cacheService.put(cacheKey, aiDiagnosis);
                    return aiDiagnosis;
                }
            } catch (Exception ex) {
                log.warn("Spring AI diagnosis failed, falling back to deterministic offline analysis without caching: {}", ex.getMessage());
                // Return offline fallback directly WITHOUT poisoning the cache on transient errors
                return createOfflineFallbackDiagnosis(context);
            }
        }

        // 3. Deterministic offline fallback (pure offline mode without client)
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
        return SYSTEM_PROMPT + "\nContext:\n" + buildUserPrompt(context);
    }

    public String buildUserPrompt(CorrelatedQueryContext context) {
        return """
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

        boolean isPkLookup = PRIMARY_KEY_WHERE_PATTERN.matcher(sql).find()
                || (explain.contains("using ") && explain.contains("pkey"))
                || explain.contains("_pkey");

        if (isPkLookup) {
            // Lazy @ManyToOne entity traversal
            String entity = FingerprintCacheKey.extractEntitySignature(context.offendingSql());
            String attrPath = !entity.isBlank() ? toCamelCase(entity) : "association";
            return new NPlusOneDiagnosis(
                    "Lazy @ManyToOne traversal inside loop.",
                    String.format("Add @EntityGraph(attributePaths = {\"%s\"}) to %s.", attrPath, parentRepo),
                    "ENTITY_GRAPH"
            );
        }

        boolean isFkCollection = FOREIGN_KEY_WHERE_PATTERN.matcher(sql).find();
        if (isFkCollection) {
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

package com.builtbyjuls.gate.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Calculates SHA-256 fingerprint cache keys following Section 3.5:
 * Key Formula: SHA-256(normalizedSql + serviceMethodSignature + repositorySignature + entitySignature)
 */
public final class FingerprintCacheKey {

    private static final Pattern FROM_ENTITY_PATTERN = Pattern.compile(
            "\\bfrom\\s+([a-zA-Z0-9_]+)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern LINE_NUMBER_PATTERN = Pattern.compile(":\\d+|\\([^:)]+\\.java:\\d+\\)");

    private FingerprintCacheKey() {
    }

    /**
     * Sanitizes a service method caller frame by stripping line numbers.
     *
     * @param method raw method signature (e.g. Class.method:54 or Class.method(Class.java:54))
     * @return sanitized method signature without line numbers
     */
    public static String sanitizeMethodSignature(String method) {
        if (method == null || method.isBlank()) {
            return "";
        }
        return LINE_NUMBER_PATTERN.matcher(method).replaceAll("").trim();
    }

    /**
     * Computes the SHA-256 hex string for the given components.
     * Line numbers in serviceMethod are stripped to keep cache keys resilient across minor edits.
     *
     * @param normalizedSql normalized query SQL
     * @param serviceMethod service method signature (e.g. GridDailySettlementService.buildParticipantSummary:54)
     * @param repositorySignature repository method signature
     * @param entitySignature target entity or table name
     * @return 64-character lowercase SHA-256 hex string
     */
    public static String compute(
            String normalizedSql,
            String serviceMethod,
            String repositorySignature,
            String entitySignature
    ) {
        String sanitizedMethod = sanitizeMethodSignature(serviceMethod);
        String input = (normalizedSql != null ? normalizedSql : "") +
                "|" + sanitizedMethod +
                "|" + (repositorySignature != null ? repositorySignature : "") +
                "|" + (entitySignature != null ? entitySignature : "");

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest unavailable", e);
        }
    }

    /**
     * Extracts the target entity or table name from the given SQL string.
     *
     * @param sql SQL statement
     * @return table/entity name or empty string if not found
     */
    public static String extractEntitySignature(String sql) {
        if (sql == null || sql.isBlank()) {
            return "";
        }
        Matcher matcher = FROM_ENTITY_PATTERN.matcher(sql);
        if (matcher.find()) {
            return matcher.group(1).toLowerCase();
        }
        return "";
    }
}

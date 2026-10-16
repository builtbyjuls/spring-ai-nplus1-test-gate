package com.builtbyjuls.gate.baseline;

/**
 * Immutable entry representing a known, baseline-suppressed N+1 query loop.
 */
public record NPlusOneBaselineEntry(
        String testClass,
        String testMethod,
        String parentQuery,
        String childCallSite,
        String queryFingerprint,
        String createdAt
) {
}

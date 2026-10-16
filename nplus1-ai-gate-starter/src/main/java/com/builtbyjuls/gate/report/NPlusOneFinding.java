package com.builtbyjuls.gate.report;

import com.builtbyjuls.gate.config.GateMode;

import java.time.Instant;

/**
 * Diagnostic finding representing an N+1 query loop detection.
 */
public record NPlusOneFinding(
        String testClass,
        String testMethod,
        GateMode mode,
        int repeatedQueryCount,
        int threshold,
        String parentQuery,
        String childCallSite,
        String offendingSql,
        String explainPlan,
        String diagnosis,
        String recommendedFix,
        Instant timestamp,
        boolean baselineSuppressed,
        String suppressionReason
) {

    public NPlusOneFinding(
            String testClass,
            String testMethod,
            GateMode mode,
            int repeatedQueryCount,
            int threshold,
            String parentQuery,
            String childCallSite,
            String offendingSql,
            String explainPlan,
            String diagnosis,
            String recommendedFix,
            Instant timestamp
    ) {
        this(testClass, testMethod, mode, repeatedQueryCount, threshold, parentQuery, childCallSite,
                offendingSql, explainPlan, diagnosis, recommendedFix, timestamp, false, null);
    }
}

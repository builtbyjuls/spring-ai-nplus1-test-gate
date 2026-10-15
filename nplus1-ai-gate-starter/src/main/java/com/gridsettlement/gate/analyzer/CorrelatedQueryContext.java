package com.gridsettlement.gate.analyzer;

import com.gridsettlement.gate.interceptor.RecordedQuery;

/**
 * Immutable correlation context pairing an offending N+1 child query loop with its
 * corresponding parent (1) query and PostgreSQL explain plan.
 *
 * @param childGroup the flagged repeated query group
 * @param parentQuery the preceding single query that loaded the parent entities (or null if standalone)
 * @param parentRepositorySignature signature of the parent repository method (or fallback text)
 * @param explainPlan pruned explain plan from PostgreSQL
 */
public record CorrelatedQueryContext(
        QueryGroup childGroup,
        RecordedQuery parentQuery,
        String parentRepositorySignature,
        String explainPlan
) {

    public String childCallSite() {
        return childGroup != null ? childGroup.callSiteFrame() : "unknown";
    }

    public String offendingSql() {
        return childGroup != null ? childGroup.normalizedSql() : "";
    }

    public int executionCount() {
        return childGroup != null ? childGroup.executionCount() : 0;
    }

    public String parentSql() {
        return parentQuery != null ? parentQuery.rawSql() : null;
    }
}

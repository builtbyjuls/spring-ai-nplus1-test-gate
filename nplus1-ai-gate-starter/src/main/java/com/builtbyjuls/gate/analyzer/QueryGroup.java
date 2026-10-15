package com.builtbyjuls.gate.analyzer;

import com.builtbyjuls.gate.interceptor.RecordedQuery;

import java.util.List;

/**
 * Aggregated query execution group sharing the same normalized SQL and application call site.
 *
 * @param normalizedSql normalized SQL query string
 * @param callSiteFrame formatted call site location (e.g. ServiceClass.method:line)
 * @param topCallSite top application stack frame
 * @param queries all recorded query executions in this group
 */
public record QueryGroup(
        String normalizedSql,
        String callSiteFrame,
        StackTraceElement topCallSite,
        List<RecordedQuery> queries
) {

    /**
     * Number of executions captured for this query group.
     */
    public int executionCount() {
        return queries.size();
    }

    /**
     * First recorded query in this group, useful for EXPLAIN replay and parameter sample inspection.
     */
    public RecordedQuery firstQuery() {
        return queries.isEmpty() ? null : queries.get(0);
    }
}

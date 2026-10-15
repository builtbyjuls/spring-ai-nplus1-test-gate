package com.builtbyjuls.gate.analyzer;

import com.builtbyjuls.gate.interceptor.RecordedQuery;
import com.builtbyjuls.gate.normalizer.SqlNormalizer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Analyzes captured queries by filtering false positives and grouping by (normalizedSql, callSite).
 * Flags groups whose execution count reaches or exceeds the configured threshold.
 */
public class QueryAnalyzer {

    /**
     * Analyzes recorded queries against the threshold and base package.
     *
     * @param queries captured queries from NPlusOneGateScope
     * @param threshold repeated query threshold (e.g. 5)
     * @param basePackage optional application package filter
     * @return list of flagged query groups exceeding the threshold, ordered by execution count descending
     */
    public List<QueryGroup> analyze(List<RecordedQuery> queries, int threshold, String basePackage) {
        if (queries == null || queries.isEmpty() || threshold <= 1) {
            return List.of();
        }

        // Map key is: normalizedSql + "|||" + callSiteFrame
        Map<String, GroupAccumulator> accumulators = new LinkedHashMap<>();

        for (RecordedQuery query : queries) {
            String rawSql = query.rawSql();

            // 1. Drop non-SELECT queries
            if (!SqlNormalizer.isSelect(rawSql)) {
                continue;
            }

            // 2. Drop sequence queries (nextval/currval)
            if (SqlNormalizer.isSequenceQuery(rawSql)) {
                continue;
            }

            // 3. Drop Hibernate @BatchSize queries (batchFetchQueue == true)
            if (query.batchFetchQueue()) {
                continue;
            }

            String normalizedSql = SqlNormalizer.normalize(rawSql);
            StackTraceElement callSite = extractTopCallSite(query.stackFrames(), basePackage);
            String callSiteFrame = formatCallSite(callSite);

            String groupKey = normalizedSql + "|||" + callSiteFrame;
            accumulators.computeIfAbsent(groupKey, k -> new GroupAccumulator(normalizedSql, callSiteFrame, callSite))
                    .addQuery(query);
        }

        List<QueryGroup> violations = new ArrayList<>();
        for (GroupAccumulator acc : accumulators.values()) {
            List<RecordedQuery> groupQueries = acc.queries;

            // 4. Drop intentional pagination where offset parameter strictly increases
            if (SqlNormalizer.isPagination(groupQueries)) {
                continue;
            }

            // 5. Flag groups reaching or exceeding threshold
            if (groupQueries.size() >= threshold) {
                violations.add(new QueryGroup(
                        acc.normalizedSql,
                        acc.callSiteFrame,
                        acc.topCallSite,
                        List.copyOf(groupQueries)
                ));
            }
        }

        violations.sort(Comparator.comparingInt(QueryGroup::executionCount).reversed());
        return List.copyOf(violations);
    }

    /**
     * Extracts the top relevant application stack frame, skipping dynamic proxies and infrastructure.
     *
     * @param frames stack frames from RecordedQuery
     * @param basePackage optional base package to prioritize
     * @return top application stack frame or null if none found
     */
    public static StackTraceElement extractTopCallSite(List<StackTraceElement> frames, String basePackage) {
        if (frames == null || frames.isEmpty()) {
            return null;
        }

        // First pass: find first frame matching basePackage and not a proxy
        if (basePackage != null && !basePackage.isBlank()) {
            for (StackTraceElement frame : frames) {
                if (frame.getClassName().startsWith(basePackage) && !isProxyOrGenerated(frame.getClassName())) {
                    return frame;
                }
            }
        }

        // Second pass: find first frame that is not a proxy/synthetic class
        for (StackTraceElement frame : frames) {
            if (!isProxyOrGenerated(frame.getClassName())) {
                return frame;
            }
        }

        return frames.get(0);
    }

    /**
     * Formats a stack frame into ClassName.methodName(FileName:LineNumber).
     *
     * @param frame stack trace element
     * @return formatted string representation
     */
    public static String formatCallSite(StackTraceElement frame) {
        if (frame == null) {
            return "unknown";
        }
        String fileName = frame.getFileName() != null ? frame.getFileName() : "Unknown Source";
        int line = frame.getLineNumber();
        String lineStr = line >= 0 ? ":" + line : "";
        return frame.getClassName() + "." + frame.getMethodName() + "(" + fileName + lineStr + ")";
    }

    private static boolean isProxyOrGenerated(String className) {
        return className.contains("$$")
                || className.contains("$HibernateProxy")
                || className.contains("ByteBuddy")
                || className.contains("$EnhancerBySpringCGLIB")
                || className.contains("jdk.proxy");
    }

    private static class GroupAccumulator {
        final String normalizedSql;
        final String callSiteFrame;
        final StackTraceElement topCallSite;
        final List<RecordedQuery> queries = new ArrayList<>();

        GroupAccumulator(String normalizedSql, String callSiteFrame, StackTraceElement topCallSite) {
            this.normalizedSql = normalizedSql;
            this.callSiteFrame = callSiteFrame;
            this.topCallSite = topCallSite;
        }

        void addQuery(RecordedQuery query) {
            queries.add(query);
        }
    }
}

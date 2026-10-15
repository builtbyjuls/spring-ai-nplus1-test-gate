package com.builtbyjuls.gate.interceptor;

import net.ttddyy.dsproxy.proxy.ParameterSetOperation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Immutable data holder representing an intercepted JDBC query execution.
 *
 * @param rawSql the raw SQL string with parameter placeholders
 * @param parameters the captured JDBC parameter set operations
 * @param stackFrames the captured application stack frames
 * @param batchFetchQueue true if executed under Hibernate BatchFetchQueue (@BatchSize)
 * @param timestamp the instant the query was recorded
 */
public record RecordedQuery(
        String rawSql,
        List<ParameterSetOperation> parameters,
        List<StackTraceElement> stackFrames,
        boolean batchFetchQueue,
        Instant timestamp
) {

    public RecordedQuery {
        Objects.requireNonNull(rawSql, "rawSql must not be null");
        parameters = parameters != null ? List.copyOf(parameters) : List.of();
        stackFrames = stackFrames != null ? List.copyOf(stackFrames) : List.of();
        timestamp = timestamp != null ? timestamp : Instant.now();
    }

    public RecordedQuery(
            String rawSql,
            List<ParameterSetOperation> parameters,
            List<StackTraceElement> stackFrames,
            boolean batchFetchQueue
    ) {
        this(rawSql, parameters, stackFrames, batchFetchQueue, Instant.now());
    }
}

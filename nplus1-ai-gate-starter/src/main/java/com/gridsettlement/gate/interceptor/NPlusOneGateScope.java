package com.gridsettlement.gate.interceptor;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Thread-safe scope managing recorded queries across virtual threads, parallel streams,
 * and background workers, with recursion guard for diagnostic inspection.
 */
public final class NPlusOneGateScope {

    private static final ConcurrentLinkedQueue<RecordedQuery> RECORDED_QUERIES = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean IN_DIAGNOSTIC_MODE = new AtomicBoolean(false);

    private NPlusOneGateScope() {
    }

    /**
     * Records a query if not currently inside diagnostic mode.
     *
     * @param query the recorded query to append
     */
    public static void record(RecordedQuery query) {
        if (!IN_DIAGNOSTIC_MODE.get() && query != null) {
            RECORDED_QUERIES.add(query);
        }
    }

    /**
     * Returns an immutable snapshot list of all currently recorded queries.
     *
     * @return list of recorded queries
     */
    public static List<RecordedQuery> getRecordedQueries() {
        return List.copyOf(RECORDED_QUERIES);
    }

    /**
     * Clears all recorded queries. Typically called to clear test setup queries.
     */
    public static void reset() {
        RECORDED_QUERIES.clear();
    }

    /**
     * Resets recorded queries and executes the target action.
     *
     * @param action the test action to measure
     */
    public static void measure(Runnable action) {
        reset();
        action.run();
    }

    /**
     * Resets recorded queries and executes the target action, returning its result.
     *
     * @param action the test action to measure
     * @param <T> the return type
     * @return the result of the action
     */
    public static <T> T measure(Supplier<T> action) {
        reset();
        return action.get();
    }

    /**
     * Checks if the execution is currently in diagnostic mode (e.g. running EXPLAIN or flush).
     *
     * @return true if diagnostic mode is active
     */
    public static boolean isInDiagnosticMode() {
        return IN_DIAGNOSTIC_MODE.get();
    }

    /**
     * Enables or disables diagnostic mode.
     *
     * @param enabled true to enable diagnostic mode
     */
    public static void setDiagnosticMode(boolean enabled) {
        IN_DIAGNOSTIC_MODE.set(enabled);
    }

    /**
     * Executes the given action inside diagnostic mode, restoring previous state on completion.
     *
     * @param action the diagnostic action to execute
     */
    public static void withDiagnosticMode(Runnable action) {
        boolean previous = IN_DIAGNOSTIC_MODE.getAndSet(true);
        try {
            action.run();
        } finally {
            IN_DIAGNOSTIC_MODE.set(previous);
        }
    }

    /**
     * Executes the given supplier inside diagnostic mode, restoring previous state on completion.
     *
     * @param action the diagnostic supplier to execute
     * @param <T> the return type
     * @return the result of the supplier
     */
    public static <T> T withDiagnosticMode(Supplier<T> action) {
        boolean previous = IN_DIAGNOSTIC_MODE.getAndSet(true);
        try {
            return action.get();
        } finally {
            IN_DIAGNOSTIC_MODE.set(previous);
        }
    }
}

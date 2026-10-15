package com.gridsettlement.gate.collector;

import java.lang.StackWalker.Option;
import java.lang.StackWalker.StackFrame;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Filters call stack frames to extract application call sites and detect
 * Hibernate BatchFetchQueue (@BatchSize) executions.
 */
public final class StackTraceFilter {

    private static final String BATCH_FETCH_QUEUE_MARKER = "org.hibernate.engine.spi.BatchFetchQueue";

    private static final Set<String> IGNORED_PACKAGE_PREFIXES = Set.of(
            "java.",
            "javax.",
            "jakarta.",
            "jdk.",
            "sun.",
            "net.ttddyy.dsproxy.",
            "com.zaxxer.hikari.",
            "org.postgresql.",
            "org.h2.",
            "org.hibernate.",
            "org.springframework.",
            "org.junit."
    );

    private static final Set<String> IGNORED_STARTER_CLASSES = Set.of(
            "com.gridsettlement.gate.interceptor.NPlusOneQueryExecutionListener",
            "com.gridsettlement.gate.interceptor.NPlusOneGateScope",
            "com.gridsettlement.gate.collector.StackTraceFilter"
    );

    private static final StackWalker WALKER = StackWalker.getInstance(Option.RETAIN_CLASS_REFERENCE);

    private StackTraceFilter() {
    }

    /**
     * Inspection result containing filtered application stack frames and batch fetch status.
     *
     * @param userFrames application call site frames
     * @param batchFetchQueue true if BatchFetchQueue was present in call stack
     */
    public record StackInspectionResult(
            List<StackTraceElement> userFrames,
            boolean batchFetchQueue
    ) {}

    /**
     * Inspects the current thread call stack to find application frames and check for BatchFetchQueue.
     *
     * @return stack inspection result
     */
    public static StackInspectionResult inspectStack() {
        return inspectStack(null);
    }

    /**
     * Inspects the current thread call stack, optionally prioritizing a specific base package.
     *
     * @param basePackage optional application base package filter
     * @return stack inspection result
     */
    public static StackInspectionResult inspectStack(String basePackage) {
        return WALKER.walk(frames -> {
            List<StackTraceElement> appFrames = new ArrayList<>();
            boolean[] isBatch = new boolean[]{false};

            frames.forEach(frame -> {
                String className = frame.getClassName();
                if (className.contains(BATCH_FETCH_QUEUE_MARKER)) {
                    isBatch[0] = true;
                }

                if (isApplicationFrame(className, basePackage)) {
                    appFrames.add(frame.toStackTraceElement());
                }
            });

            return new StackInspectionResult(List.copyOf(appFrames), isBatch[0]);
        });
    }

    static boolean isApplicationFrame(String className, String basePackage) {
        if (IGNORED_STARTER_CLASSES.contains(className)) {
            return false;
        }
        if (basePackage != null && !basePackage.isBlank()) {
            return className.startsWith(basePackage) && !isIgnored(className);
        }
        return !isIgnored(className);
    }

    static boolean isIgnored(String className) {
        for (String prefix : IGNORED_PACKAGE_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}

package com.builtbyjuls.gate.analyzer;

import com.builtbyjuls.gate.interceptor.RecordedQuery;
import com.builtbyjuls.gate.normalizer.SqlNormalizer;
import com.builtbyjuls.gate.source.SourceLookupService;

import java.util.List;

/**
 * Correlates an offending N+1 child loop query with the single parent query that loaded
 * the root entities inside the same service method.
 */
public class ParentChildCorrelator {

    private final SourceLookupService sourceLookupService;

    public ParentChildCorrelator() {
        this(new SourceLookupService());
    }

    public ParentChildCorrelator(SourceLookupService sourceLookupService) {
        this.sourceLookupService = sourceLookupService;
    }

    /**
     * Correlates the flagged child query group with its parent (1) query and repository method.
     *
     * @param allQueries all recorded queries in execution order
     * @param childGroup the flagged N+1 query group
     * @param explainPlan pruned explain plan text
     * @return correlated query context
     */
    public CorrelatedQueryContext correlate(
            List<RecordedQuery> allQueries,
            QueryGroup childGroup,
            String explainPlan
    ) {
        if (allQueries == null || allQueries.isEmpty() || childGroup == null) {
            return new CorrelatedQueryContext(childGroup, null, "Unknown", explainPlan);
        }

        StackTraceElement childCallSite = childGroup.topCallSite();
        if (childCallSite == null) {
            return new CorrelatedQueryContext(childGroup, null, "Unknown", explainPlan);
        }

        String serviceClass = childCallSite.getClassName();
        String serviceMethod = childCallSite.getMethodName();

        // Find the index of the first query belonging to this child group
        RecordedQuery firstChildQuery = childGroup.firstQuery();
        int firstChildIndex = allQueries.indexOf(firstChildQuery);
        if (firstChildIndex < 0) {
            firstChildIndex = allQueries.size();
        }

        // Scan backward from the first child query to find the single parent query
        RecordedQuery parentQuery = null;
        StackTraceElement parentServiceFrame = null;

        for (int i = firstChildIndex - 1; i >= 0; i--) {
            RecordedQuery candidate = allQueries.get(i);
            if (!SqlNormalizer.isSelect(candidate.rawSql())) {
                continue;
            }

            StackTraceElement matchingFrame = findFrame(candidate.stackFrames(), serviceClass, serviceMethod);
            if (matchingFrame != null) {
                parentQuery = candidate;
                parentServiceFrame = matchingFrame;
                break;
            }
        }

        String repoSignature = null;
        if (parentServiceFrame != null) {
            repoSignature = sourceLookupService.findRepositoryMethodSignature(parentServiceFrame);
        }

        if (repoSignature == null) {
            if (parentQuery != null) {
                repoSignature = parentQuery.rawSql();
            } else {
                repoSignature = "Unknown";
            }
        }

        return new CorrelatedQueryContext(childGroup, parentQuery, repoSignature, explainPlan);
    }

    private StackTraceElement findFrame(List<StackTraceElement> frames, String targetClass, String targetMethod) {
        if (frames == null) {
            return null;
        }
        for (StackTraceElement frame : frames) {
            if (targetClass.equals(frame.getClassName()) && targetMethod.equals(frame.getMethodName())) {
                return frame;
            }
        }
        return null;
    }
}

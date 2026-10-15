package com.gridsettlement.gate.interceptor;

import com.gridsettlement.gate.collector.StackTraceFilter;
import com.gridsettlement.gate.collector.StackTraceFilter.StackInspectionResult;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;
import net.ttddyy.dsproxy.proxy.ParameterSetOperation;

import java.util.ArrayList;
import java.util.List;

/**
 * datasource-proxy QueryExecutionListener that captures executed SQL queries,
 * exact JDBC ParameterSetOperations, and application stack traces into NPlusOneGateScope.
 */
public class NPlusOneQueryExecutionListener implements QueryExecutionListener {

    private final String basePackage;

    public NPlusOneQueryExecutionListener() {
        this("");
    }

    public NPlusOneQueryExecutionListener(String basePackage) {
        this.basePackage = basePackage != null ? basePackage.trim() : "";
    }

    @Override
    public void beforeQuery(ExecutionInfo executionInfo, List<QueryInfo> queryInfos) {
        // No-op before query execution
    }

    @Override
    public void afterQuery(ExecutionInfo executionInfo, List<QueryInfo> queryInfos) {
        if (NPlusOneGateScope.isInDiagnosticMode()) {
            return;
        }

        if (queryInfos == null || queryInfos.isEmpty()) {
            return;
        }

        StackInspectionResult stackResult = StackTraceFilter.inspectStack(basePackage);

        for (QueryInfo queryInfo : queryInfos) {
            String sql = queryInfo.getQuery();
            if (sql == null || sql.isBlank()) {
                continue;
            }

            List<ParameterSetOperation> setOperations = extractSetOperations(queryInfo);
            RecordedQuery recordedQuery = new RecordedQuery(
                    sql,
                    setOperations,
                    stackResult.userFrames(),
                    stackResult.batchFetchQueue()
            );

            NPlusOneGateScope.record(recordedQuery);
        }
    }

    private List<ParameterSetOperation> extractSetOperations(QueryInfo queryInfo) {
        List<List<ParameterSetOperation>> parametersList = queryInfo.getParametersList();
        if (parametersList == null || parametersList.isEmpty()) {
            return List.of();
        }

        List<ParameterSetOperation> firstBatch = parametersList.get(0);
        if (firstBatch == null || firstBatch.isEmpty()) {
            return List.of();
        }

        List<ParameterSetOperation> result = new ArrayList<>();
        for (ParameterSetOperation op : firstBatch) {
            if (op != null && op.getMethod() != null && op.getMethod().getName().startsWith("set")) {
                result.add(op);
            }
        }
        return List.copyOf(result);
    }

    public String getBasePackage() {
        return basePackage;
    }
}

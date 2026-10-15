package com.gridsettlement.gate.explain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gridsettlement.gate.interceptor.RecordedQuery;
import net.ttddyy.dsproxy.proxy.ParameterSetOperation;
import org.springframework.jdbc.datasource.DataSourceUtils;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.List;
import java.util.Set;

/**
 * Runs rollback-safe EXPLAIN queries against PostgreSQL inside a transaction savepoint.
 * Forces index scans with SET LOCAL enable_seqscan = off; and prunes JSON plans to stay
 * within token limits.
 */
public class ExplainPlanRunner {

    public static final String SAVEPOINT_NAME = "NPLUS1_EXPLAIN";

    private static final Set<String> ALLOWED_JSON_FIELDS = Set.of(
            "Node Type",
            "Relation Name",
            "Index Name",
            "Filter",
            "Index Cond"
    );

    private final ObjectMapper objectMapper;

    public ExplainPlanRunner() {
        this(new ObjectMapper());
    }

    public ExplainPlanRunner(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Executes safe, rollback-guaranteed EXPLAIN on the given sample query and returns a pruned plan summary.
     *
     * @param dataSource application DataSource
     * @param sampleQuery query execution containing raw SQL and captured parameters
     * @return pruned plan summary or fallback message
     */
    public String executeExplain(DataSource dataSource, RecordedQuery sampleQuery) {
        if (dataSource == null || sampleQuery == null || sampleQuery.rawSql() == null) {
            return "EXPLAIN unavailable (no query or datasource)";
        }

        Connection conn = DataSourceUtils.getConnection(dataSource);
        Savepoint savepoint = null;
        boolean wasAutoCommit = false;

        try {
            DatabaseMetaData meta = conn.getMetaData();
            boolean isPostgreSql = meta.getDatabaseProductName().toLowerCase().contains("postgres");

            // Transaction / savepoint isolation
            if (conn.getAutoCommit()) {
                wasAutoCommit = true;
                conn.setAutoCommit(false);
            } else {
                savepoint = conn.setSavepoint(SAVEPOINT_NAME);
            }

            // Disable sequential scans so PostgreSQL prefers index scans even on small test tables
            if (isPostgreSql) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("SET LOCAL enable_seqscan = off;");
                }
            }

            String explainSql = isPostgreSql
                    ? "EXPLAIN (FORMAT JSON) " + sampleQuery.rawSql()
                    : "EXPLAIN " + sampleQuery.rawSql();

            String rawOutput = null;
            try (PreparedStatement ps = conn.prepareStatement(explainSql)) {
                replayParameters(ps, sampleQuery.parameters());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        rawOutput = rs.getString(1);
                    }
                }
            }

            if (rawOutput != null && isPostgreSql) {
                return formatPostgreSqlPlan(rawOutput);
            } else if (rawOutput != null) {
                return rawOutput.trim();
            }
            return "EXPLAIN returned empty output";

        } catch (Exception e) {
            return "EXPLAIN execution failed: " + e.getMessage();
        } finally {
            // Roll back savepoint or temporary transaction to ensure absolute DB safety
            try {
                if (savepoint != null && !conn.isClosed()) {
                    conn.rollback(savepoint);
                } else if (wasAutoCommit && !conn.isClosed()) {
                    conn.rollback();
                    conn.setAutoCommit(true);
                }
            } catch (Exception ignored) {
            }
            DataSourceUtils.releaseConnection(conn, dataSource);
        }
    }

    /**
     * Replays captured parameter setter operations onto the target prepared statement.
     *
     * @param ps PreparedStatement
     * @param operations captured parameter operations
     */
    public static void replayParameters(PreparedStatement ps, List<ParameterSetOperation> operations) {
        if (operations == null || operations.isEmpty()) {
            return;
        }

        for (ParameterSetOperation op : operations) {
            Method method = op.getMethod();
            Object[] args = op.getArgs();
            if (method != null && args != null) {
                try {
                    method.setAccessible(true);
                    method.invoke(ps, args);
                } catch (Exception e) {
                    if (args.length >= 2 && args[0] instanceof Integer idx) {
                        try {
                            ps.setObject(idx, args[1]);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }
    }

    /**
     * Formats a raw PostgreSQL EXPLAIN (FORMAT JSON) string into a concise, human-readable summary.
     * Example: "Index Scan on bilateral_contract using bilateral_contract_pkey"
     *
     * @param jsonText raw JSON from PostgreSQL EXPLAIN
     * @return formatted plan summary
     */
    public String formatPostgreSqlPlan(String jsonText) {
        try {
            JsonNode root = objectMapper.readTree(jsonText);
            JsonNode planNode = extractPlanNode(root);
            if (planNode == null) {
                return jsonText.trim();
            }

            String nodeType = planNode.path("Node Type").asText("");
            String relation = planNode.path("Relation Name").asText("");
            String index = planNode.path("Index Name").asText("");
            String cond = planNode.has("Index Cond") ? planNode.get("Index Cond").asText()
                    : (planNode.has("Filter") ? planNode.get("Filter").asText() : "");

            StringBuilder sb = new StringBuilder();
            sb.append(nodeType);
            if (!relation.isBlank()) {
                sb.append(" on ").append(relation);
            }
            if (!index.isBlank()) {
                sb.append(" using ").append(index);
            }
            if (!cond.isBlank()) {
                sb.append(" (").append(cond).append(")");
            }
            return sb.toString();
        } catch (Exception e) {
            return jsonText.trim();
        }
    }

    /**
     * Prunes a raw PostgreSQL EXPLAIN JSON tree down to essential fields under the token budget.
     *
     * @param jsonText raw JSON from PostgreSQL EXPLAIN
     * @return pruned compact JSON string
     */
    public String pruneJsonPlan(String jsonText) {
        try {
            JsonNode root = objectMapper.readTree(jsonText);
            JsonNode planNode = extractPlanNode(root);
            if (planNode == null) {
                return "{}";
            }
            ObjectNode pruned = objectMapper.createObjectNode();
            pruneNodeRecursive(planNode, pruned);
            return objectMapper.writeValueAsString(pruned);
        } catch (Exception e) {
            return "{}";
        }
    }

    private JsonNode extractPlanNode(JsonNode root) {
        if (root.isArray() && !root.isEmpty()) {
            JsonNode first = root.get(0);
            if (first.has("Plan")) {
                return first.get("Plan");
            }
        }
        return root.has("Plan") ? root.get("Plan") : null;
    }

    private void pruneNodeRecursive(JsonNode source, ObjectNode target) {
        for (String field : ALLOWED_JSON_FIELDS) {
            if (source.has(field)) {
                target.set(field, source.get(field));
            }
        }

        if (source.has("Plans") && source.get("Plans").isArray()) {
            ArrayNode targetPlans = target.putArray("Plans");
            for (JsonNode child : source.get("Plans")) {
                ObjectNode childTarget = objectMapper.createObjectNode();
                pruneNodeRecursive(child, childTarget);
                targetPlans.add(childTarget);
            }
        }
    }
}

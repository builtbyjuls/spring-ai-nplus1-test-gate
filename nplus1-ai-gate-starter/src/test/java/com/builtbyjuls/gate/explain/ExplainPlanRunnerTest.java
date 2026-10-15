package com.builtbyjuls.gate.explain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExplainPlanRunnerTest {

    private final ExplainPlanRunner runner = new ExplainPlanRunner();

    private static final String SAMPLE_POSTGRES_INDEX_SCAN = """
            [
              {
                "Plan": {
                  "Node Type": "Index Scan",
                  "Parallel Aware": false,
                  "Async Capable": false,
                  "Scan Direction": "NoMovement",
                  "Index Name": "bilateral_contract_pkey",
                  "Relation Name": "bilateral_contract",
                  "Schema": "public",
                  "Alias": "bc1_0",
                  "Startup Cost": 0.15,
                  "Total Cost": 8.17,
                  "Plan Rows": 1,
                  "Plan Width": 32,
                  "Actual Startup Time": 0.015,
                  "Actual Total Time": 0.016,
                  "Actual Rows": 1,
                  "Actual Loops": 1,
                  "Index Cond": "(id = $1)",
                  "Rows Removed by Index Recheck": 0
                }
              }
            ]
            """;

    private static final String SAMPLE_POSTGRES_SEQ_SCAN = """
            [
              {
                "Plan": {
                  "Node Type": "Seq Scan",
                  "Relation Name": "market_participant",
                  "Alias": "mp1_0",
                  "Filter": "(role = 'GENERATOR')"
                }
              }
            ]
            """;

    @Test
    @DisplayName("Should format PostgreSQL Index Scan into human-readable summary")
    void shouldFormatPostgreSqlIndexScan() {
        String summary = runner.formatPostgreSqlPlan(SAMPLE_POSTGRES_INDEX_SCAN);
        assertThat(summary).isEqualTo("Index Scan on bilateral_contract using bilateral_contract_pkey ((id = $1))");
    }

    @Test
    @DisplayName("Should format PostgreSQL Seq Scan into human-readable summary")
    void shouldFormatPostgreSqlSeqScan() {
        String summary = runner.formatPostgreSqlPlan(SAMPLE_POSTGRES_SEQ_SCAN);
        assertThat(summary).isEqualTo("Seq Scan on market_participant ((role = 'GENERATOR'))");
    }

    @Test
    @DisplayName("Should prune verbose JSON plan to essential fields within token budget")
    void shouldPruneVerboseJsonPlan() {
        String pruned = runner.pruneJsonPlan(SAMPLE_POSTGRES_INDEX_SCAN);

        assertThat(pruned).contains("\"Node Type\":\"Index Scan\"");
        assertThat(pruned).contains("\"Relation Name\":\"bilateral_contract\"");
        assertThat(pruned).contains("\"Index Name\":\"bilateral_contract_pkey\"");
        assertThat(pruned).contains("\"Index Cond\":\"(id = $1)\"");

        // Stripped metrics that inflate token consumption
        assertThat(pruned).doesNotContain("Parallel Aware");
        assertThat(pruned).doesNotContain("Actual Startup Time");
        assertThat(pruned).doesNotContain("Rows Removed by Index Recheck");
        assertThat(pruned).doesNotContain("Plan Width");
    }
}

package com.gridsettlement.gate.ai.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Structured AI diagnosis response recommending concrete Java fixes for Hibernate N+1 loops.
 * Compatible with Jackson 2 and Jackson 3.
 *
 * @param diagnosis concise explanation of the root cause
 * @param recommendedFix actionable code change (e.g. @EntityGraph or JPQL DTO aggregation)
 * @param fixType classification category (e.g. ENTITY_GRAPH, JPQL_DTO_AGGREGATION, BATCH_SIZE)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NPlusOneDiagnosis(
        @JsonProperty("diagnosis") String diagnosis,
        @JsonProperty("recommendedFix") String recommendedFix,
        @JsonProperty("fixType") String fixType
) {

    @JsonCreator
    public NPlusOneDiagnosis(
            @JsonProperty("diagnosis") String diagnosis,
            @JsonProperty("recommendedFix") String recommendedFix,
            @JsonProperty("fixType") String fixType
    ) {
        this.diagnosis = diagnosis != null ? diagnosis : "";
        this.recommendedFix = recommendedFix != null ? recommendedFix : "";
        this.fixType = fixType != null ? fixType : "UNKNOWN";
    }
}

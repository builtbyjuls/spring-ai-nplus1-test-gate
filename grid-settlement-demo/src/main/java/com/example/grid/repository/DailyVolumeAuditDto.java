package com.example.grid.repository;

import java.math.BigDecimal;

/**
 * Aggregated daily volume audit projecting total declared MWh directly from SQL.
 */
public record DailyVolumeAuditDto(
        Long declarationId,
        String contractCode,
        Long intervalCount,
        BigDecimal totalMwh
) {}

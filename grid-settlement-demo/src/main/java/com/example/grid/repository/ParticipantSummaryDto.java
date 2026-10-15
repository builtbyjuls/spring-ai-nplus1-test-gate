package com.example.grid.repository;

/**
 * Summary DTO projecting contract declaration and counterparty details.
 */
public record ParticipantSummaryDto(
        Long declarationId,
        String contractCode,
        String buyerCode,
        String buyerName
) {}

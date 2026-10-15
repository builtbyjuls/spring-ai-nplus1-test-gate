package com.builtbyjuls.grid.service;

import com.builtbyjuls.grid.domain.ContractDeclaration;
import com.builtbyjuls.grid.domain.IntervalQuantity;
import com.builtbyjuls.grid.repository.ContractDeclarationRepository;
import com.builtbyjuls.grid.repository.DailyVolumeAuditDto;
import com.builtbyjuls.grid.repository.ParticipantSummaryDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Wholesale electricity market settlement service coordinating daily volume
 * audit calculations and counterparty position summaries.
 */
@Service
@Transactional(readOnly = true)
public class GridDailySettlementService {

    private final ContractDeclarationRepository contractDeclarationRepository;

    public GridDailySettlementService(ContractDeclarationRepository contractDeclarationRepository) {
        this.contractDeclarationRepository = Objects.requireNonNull(
                contractDeclarationRepository,
                "contractDeclarationRepository must not be null"
        );
    }

    /**
     * Scenario A Unoptimized: Traverses lazy bilateral contract and buyer references inside loop,
     * triggering N+1 query loop.
     *
     * @param date trading date to summarize
     * @return participant summary DTOs
     */
    public List<ParticipantSummaryDto> buildParticipantSummary(LocalDate date) {
        List<ContractDeclaration> declarations = contractDeclarationRepository.findConfirmedForParticipantSummary(date);
        List<ParticipantSummaryDto> summaries = new ArrayList<>();

        for (ContractDeclaration declaration : declarations) {
            String contractCode = declaration.getBilateralContract().getContractCode();
            String buyerCode = declaration.getBilateralContract().getBuyer().getParticipantCode();
            String buyerName = declaration.getBilateralContract().getBuyer().getShortName();

            summaries.add(new ParticipantSummaryDto(declaration.getId(), contractCode, buyerCode, buyerName));
        }

        return summaries;
    }

    /**
     * Scenario A Optimized: Uses eager entity graph to load declaration, contract, and buyer in one query.
     *
     * @param date trading date to summarize
     * @return participant summary DTOs
     */
    public List<ParticipantSummaryDto> buildParticipantSummaryOptimized(LocalDate date) {
        List<ContractDeclaration> declarations = contractDeclarationRepository.findConfirmedForParticipantSummaryOptimized(date);
        List<ParticipantSummaryDto> summaries = new ArrayList<>();

        for (ContractDeclaration declaration : declarations) {
            String contractCode = declaration.getBilateralContract().getContractCode();
            String buyerCode = declaration.getBilateralContract().getBuyer().getParticipantCode();
            String buyerName = declaration.getBilateralContract().getBuyer().getShortName();

            summaries.add(new ParticipantSummaryDto(declaration.getId(), contractCode, buyerCode, buyerName));
        }

        return summaries;
    }

    /**
     * Scenario B Unoptimized: Traverses lazy 288-row interval collections inside loop,
     * loading 5,760 entities into memory across 20 separate child SELECT queries.
     *
     * @param date trading date to audit
     * @return daily volume audit DTOs
     */
    public List<DailyVolumeAuditDto> buildDailyVolumeAudit(LocalDate date) {
        List<ContractDeclaration> declarations = contractDeclarationRepository.findConfirmedForDailyVolumeAudit(date);
        List<DailyVolumeAuditDto> auditResults = new ArrayList<>();

        for (ContractDeclaration declaration : declarations) {
            String contractCode = declaration.getBilateralContract().getContractCode();
            List<IntervalQuantity> intervals = declaration.getIntervals();

            BigDecimal totalMwh = intervals.stream()
                    .map(IntervalQuantity::getDeclaredMwh)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            auditResults.add(new DailyVolumeAuditDto(
                    declaration.getId(),
                    contractCode,
                    (long) intervals.size(),
                    totalMwh
            ));
        }

        return auditResults;
    }

    /**
     * Scenario B Optimized: Uses JPQL DTO aggregation executed directly inside PostgreSQL engine.
     *
     * @param date trading date to audit
     * @return daily volume audit DTOs
     */
    public List<DailyVolumeAuditDto> buildDailyVolumeAuditOptimized(LocalDate date) {
        return contractDeclarationRepository.findDailyVolumeSummaryOptimized(date);
    }
}

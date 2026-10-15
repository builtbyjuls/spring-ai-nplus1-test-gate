package com.example.grid.repository;

import com.example.grid.domain.ContractDeclaration;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface ContractDeclarationRepository extends JpaRepository<ContractDeclaration, Long> {

    /**
     * Scenario A Unoptimized: Loads declarations with lazy bilateralContract and buyer.
     * Iterating declarations and navigating bilateralContract.buyer triggers an N+1 query loop.
     */
    @Query("SELECT d FROM ContractDeclaration d " +
           "WHERE d.tradingDate = :date AND d.status = com.example.grid.domain.DeclarationStatus.CONFIRMED " +
           "ORDER BY d.id")
    List<ContractDeclaration> findConfirmedForParticipantSummary(@Param("date") LocalDate date);

    /**
     * Scenario A Optimized: Uses @EntityGraph to eagerly fetch bilateralContract and buyer in a single query.
     */
    @EntityGraph(attributePaths = {"bilateralContract", "bilateralContract.buyer"})
    @Query("SELECT d FROM ContractDeclaration d " +
           "WHERE d.tradingDate = :date AND d.status = com.example.grid.domain.DeclarationStatus.CONFIRMED " +
           "ORDER BY d.id")
    List<ContractDeclaration> findConfirmedForParticipantSummaryOptimized(@Param("date") LocalDate date);

    /**
     * Scenario B Unoptimized: Loads declarations with bilateralContract eagerly but without fetching 288-row interval collections.
     * Iterating declarations and calling declaration.getIntervals() triggers 20 child queries.
     */
    @EntityGraph(attributePaths = {"bilateralContract"})
    @Query("SELECT d FROM ContractDeclaration d " +
           "WHERE d.tradingDate = :date AND d.status = com.example.grid.domain.DeclarationStatus.CONFIRMED " +
           "ORDER BY d.id")
    List<ContractDeclaration> findConfirmedForDailyVolumeAudit(@Param("date") LocalDate date);

    /**
     * Scenario B Optimized: Runs JPQL aggregation directly in database, returning DailyVolumeAuditDto
     * without hydrating 5,760 entities into JVM heap memory.
     */
    @Query("SELECT new com.example.grid.repository.DailyVolumeAuditDto(" +
           "d.id, c.contractCode, COUNT(i.id), SUM(i.declaredMwh)) " +
           "FROM ContractDeclaration d " +
           "JOIN d.bilateralContract c " +
           "JOIN d.intervals i " +
           "WHERE d.tradingDate = :date AND d.status = com.example.grid.domain.DeclarationStatus.CONFIRMED " +
           "GROUP BY d.id, c.contractCode " +
           "ORDER BY d.id")
    List<DailyVolumeAuditDto> findDailyVolumeSummaryOptimized(@Param("date") LocalDate date);
}

package com.builtbyjuls.grid.repository;

import com.builtbyjuls.grid.domain.BilateralContract;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BilateralContractRepository extends JpaRepository<BilateralContract, Long> {

    Optional<BilateralContract> findByContractCode(String contractCode);
}

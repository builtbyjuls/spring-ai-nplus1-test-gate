package com.builtbyjuls.grid.repository;

import com.builtbyjuls.grid.domain.MarketParticipant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MarketParticipantRepository extends JpaRepository<MarketParticipant, Long> {

    Optional<MarketParticipant> findByParticipantCode(String participantCode);
}

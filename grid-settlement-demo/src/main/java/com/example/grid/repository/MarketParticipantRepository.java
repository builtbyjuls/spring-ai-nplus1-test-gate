package com.example.grid.repository;

import com.example.grid.domain.MarketParticipant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MarketParticipantRepository extends JpaRepository<MarketParticipant, Long> {

    Optional<MarketParticipant> findByParticipantCode(String participantCode);
}

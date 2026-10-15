package com.example.grid.repository;

import com.example.grid.domain.MeterReadingHeader;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface MeterReadingHeaderRepository extends JpaRepository<MeterReadingHeader, Long> {

    List<MeterReadingHeader> findByReadingDate(LocalDate readingDate);
}

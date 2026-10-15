package com.builtbyjuls.grid.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import org.hibernate.annotations.BatchSize;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Daily meter reading header recording telemetry for settlement reconciliation.
 * Configured with @BatchSize(size = 20) for batch collection fetching.
 */
@Entity
@Table(name = "meter_reading_header")
public class MeterReadingHeader {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "meter_code", nullable = false, length = 64)
    private String meterCode;

    @Column(name = "reading_date", nullable = false)
    private LocalDate readingDate;

    @BatchSize(size = 20)
    @OneToMany(mappedBy = "header", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<MeterIntervalReading> readings = new ArrayList<>();

    protected MeterReadingHeader() {
    }

    public MeterReadingHeader(String meterCode, LocalDate readingDate) {
        this.meterCode = meterCode;
        this.readingDate = readingDate;
    }

    public Long getId() {
        return id;
    }

    public String getMeterCode() {
        return meterCode;
    }

    public LocalDate getReadingDate() {
        return readingDate;
    }

    public List<MeterIntervalReading> getReadings() {
        return readings;
    }

    public void addReading(MeterIntervalReading reading) {
        readings.add(reading);
        reading.setHeader(this);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MeterReadingHeader that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}

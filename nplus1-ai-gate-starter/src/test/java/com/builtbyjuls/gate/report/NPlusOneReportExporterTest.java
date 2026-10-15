package com.builtbyjuls.gate.report;

import com.builtbyjuls.gate.config.GateMode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NPlusOneReportExporterTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    @DisplayName("Should export report.json and summary.md when finding is recorded")
    void shouldExportJsonAndMarkdown(@TempDir Path tempDir) throws IOException {
        NPlusOneReportExporter exporter = new NPlusOneReportExporter();
        NPlusOneFinding finding = new NPlusOneFinding(
                "com.example.service.SettlementServiceTest",
                "testSettlementRun",
                GateMode.FAIL,
                10,
                5,
                "SettlementBatchRepository.findAll()",
                "com.example.service.SettlementService.calculate(SettlementService.java:42)",
                "SELECT * FROM meter_reading WHERE batch_id = ?",
                "Index Scan using idx_batch on meter_reading",
                "Lazy @ManyToOne association fetched in loop.",
                "@EntityGraph(attributePaths = {\"meterReadings\"})",
                Instant.now()
        );

        exporter.export(finding, tempDir.toString());

        Path jsonPath = tempDir.resolve("report.json");
        Path mdPath = tempDir.resolve("summary.md");

        assertThat(Files.exists(jsonPath)).isTrue();
        assertThat(Files.exists(mdPath)).isTrue();

        List<NPlusOneFinding> importedFindings = objectMapper.readValue(
                jsonPath.toFile(),
                new TypeReference<List<NPlusOneFinding>>() {}
        );
        assertThat(importedFindings).hasSize(1);
        NPlusOneFinding imported = importedFindings.get(0);
        assertThat(imported.testClass()).isEqualTo("com.example.service.SettlementServiceTest");
        assertThat(imported.testMethod()).isEqualTo("testSettlementRun");
        assertThat(imported.mode()).isEqualTo(GateMode.FAIL);
        assertThat(imported.repeatedQueryCount()).isEqualTo(10);
        assertThat(imported.offendingSql()).isEqualTo("SELECT * FROM meter_reading WHERE batch_id = ?");

        String mdContent = Files.readString(mdPath);
        assertThat(mdContent)
                .contains("# Hibernate N+1 AI Test Gate Summary")
                .contains("Total findings detected: **1**")
                .contains("`SettlementServiceTest`")
                .contains("`testSettlementRun`")
                .contains("`FAIL`")
                .contains("### Finding 1: `SettlementServiceTest.testSettlementRun` (FAIL)")
                .contains("* **Repeated Queries**: 10 (Threshold: 5)")
                .contains("* **Offending SQL**: `SELECT * FROM meter_reading WHERE batch_id = ?`")
                .contains("```java\n@EntityGraph(attributePaths = {\"meterReadings\"})\n```");
    }

    @Test
    @DisplayName("Should accumulate multiple findings in reports")
    void shouldAccumulateMultipleFindings(@TempDir Path tempDir) throws IOException {
        NPlusOneReportExporter exporter = new NPlusOneReportExporter();

        NPlusOneFinding finding1 = new NPlusOneFinding(
                "com.example.FirstTest",
                "testFirst",
                GateMode.FAIL,
                6,
                5,
                "FirstRepository.findAll()",
                "com.example.FirstService.run(FirstService.java:10)",
                "SELECT * FROM item WHERE id = ?",
                "Seq Scan on item",
                "Lazy item loop",
                "JOIN FETCH item",
                Instant.now()
        );

        NPlusOneFinding finding2 = new NPlusOneFinding(
                "com.example.SecondTest",
                "testSecond",
                GateMode.WARN,
                8,
                5,
                "SecondRepository.findAll()",
                "com.example.SecondService.run(SecondService.java:20)",
                "SELECT * FROM tag WHERE id = ?",
                "Index Scan on tag",
                "Lazy tag loop",
                "@BatchSize(size = 20)",
                Instant.now()
        );

        exporter.export(finding1, tempDir.toString());
        exporter.export(finding2, tempDir.toString());

        List<NPlusOneFinding> findings = exporter.getFindings();
        assertThat(findings).hasSize(2);

        String mdContent = Files.readString(tempDir.resolve("summary.md"));
        assertThat(mdContent)
                .contains("Total findings detected: **2**")
                .contains("`FirstTest`")
                .contains("`SecondTest`")
                .contains("Finding 1: `FirstTest.testFirst` (FAIL)")
                .contains("Finding 2: `SecondTest.testSecond` (WARN)");

        exporter.reset();
        assertThat(exporter.getFindings()).isEmpty();
    }

    @Test
    @DisplayName("Should safely ignore null finding")
    void shouldSafelyIgnoreNullFinding(@TempDir Path tempDir) {
        NPlusOneReportExporter exporter = new NPlusOneReportExporter();
        exporter.export(null, tempDir.toString());

        assertThat(exporter.getFindings()).isEmpty();
        assertThat(Files.exists(tempDir.resolve("report.json"))).isFalse();
    }
}

package com.builtbyjuls.gate.baseline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NPlusOneBaselineServiceTest {

    @Test
    @DisplayName("Should match baseline entry by fingerprint and simple class name")
    void shouldMatchBaselineByFingerprint(@TempDir Path tempDir) {
        NPlusOneBaselineService service = new NPlusOneBaselineService();
        NPlusOneBaselineEntry entry = new NPlusOneBaselineEntry(
                "com.example.service.OrderServiceTest",
                "testOrderDetails",
                "OrderRepository.findAll()",
                "com.example.service.OrderService.getOrder(OrderService.java:25)",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                Instant.now().toString()
        );

        service.recordBaseline(entry, tempDir.resolve("baseline.json").toString());

        // Match with full class name
        assertThat(service.isSuppressed(
                "com.example.service.OrderServiceTest",
                "testOrderDetails",
                "com.example.service.OrderService.getOrder(OrderService.java:25)",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        )).isTrue();

        // Match with simple class name
        assertThat(service.isSuppressed(
                "OrderServiceTest",
                "testOrderDetails",
                "com.example.service.OrderService.getOrder(OrderService.java:30)",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        )).isTrue();

        // Should not match different test method
        assertThat(service.isSuppressed(
                "OrderServiceTest",
                "otherTestMethod",
                "com.example.service.OrderService.getOrder(OrderService.java:25)",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        )).isFalse();
    }

    @Test
    @DisplayName("Should match baseline entry by sanitized call site frame across line number edits")
    void shouldMatchBaselineBySanitizedCallSite(@TempDir Path tempDir) {
        NPlusOneBaselineService service = new NPlusOneBaselineService();
        NPlusOneBaselineEntry entry = new NPlusOneBaselineEntry(
                "com.example.service.OrderServiceTest",
                "testOrderDetails",
                "OrderRepository.findAll()",
                "com.example.service.OrderService.getOrder(OrderService.java:25)",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                Instant.now().toString()
        );

        service.recordBaseline(entry, tempDir.resolve("baseline.json").toString());

        // Same call site, line number changed from 25 to 88, different fingerprint
        assertThat(service.isSuppressed(
                "com.example.service.OrderServiceTest",
                "testOrderDetails",
                "com.example.service.OrderService.getOrder(OrderService.java:88)",
                "different-fingerprint-due-to-whitespace"
        )).isTrue();
    }

    @Test
    @DisplayName("Should persist and reload baseline entries to/from JSON file")
    void shouldPersistAndReloadBaseline(@TempDir Path tempDir) throws IOException {
        Path baselineFile = tempDir.resolve("custom-baseline.json");
        NPlusOneBaselineService service1 = new NPlusOneBaselineService();

        NPlusOneBaselineEntry entry1 = new NPlusOneBaselineEntry(
                "com.example.FirstTest",
                "testFirst",
                "FirstRepo.findAll()",
                "FirstService.run(FirstService.java:10)",
                "fp1",
                Instant.now().toString()
        );
        NPlusOneBaselineEntry entry2 = new NPlusOneBaselineEntry(
                "com.example.SecondTest",
                "testSecond",
                "SecondRepo.findAll()",
                "SecondService.run(SecondService.java:20)",
                "fp2",
                Instant.now().toString()
        );

        service1.recordBaseline(entry1, baselineFile.toString());
        service1.recordBaseline(entry2, baselineFile.toString());

        assertThat(Files.exists(baselineFile)).isTrue();

        NPlusOneBaselineService service2 = new NPlusOneBaselineService();
        service2.loadBaseline(baselineFile.toString());

        List<NPlusOneBaselineEntry> loaded = service2.getBaselineEntries();
        assertThat(loaded).hasSize(2);
        assertThat(service2.isSuppressed("com.example.FirstTest", "testFirst", "FirstService.run(FirstService.java:10)", "fp1")).isTrue();
        assertThat(service2.isSuppressed("com.example.SecondTest", "testSecond", "SecondService.run(SecondService.java:20)", "fp2")).isTrue();
        assertThat(service2.isSuppressed("com.example.UnknownTest", "testUnknown", "call", "fp3")).isFalse();
    }

    @Test
    @DisplayName("Should safely handle non-existent file path")
    void shouldSafelyHandleNonExistentPath() {
        NPlusOneBaselineService service = new NPlusOneBaselineService();
        service.loadBaseline("non-existent-dir/non-existent-file.json");

        assertThat(service.getBaselineEntries()).isEmpty();
        assertThat(service.isSuppressed("AnyTest", "anyMethod", "call", "fp")).isFalse();
    }
}

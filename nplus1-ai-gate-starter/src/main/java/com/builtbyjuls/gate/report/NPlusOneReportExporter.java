package com.builtbyjuls.gate.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Thread-safe exporter for N+1 query diagnostic reports in JSON and Markdown formats.
 */
public class NPlusOneReportExporter {

    private static final Logger log = LoggerFactory.getLogger(NPlusOneReportExporter.class);
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final List<NPlusOneFinding> findings = Collections.synchronizedList(new ArrayList<>());

    /**
     * Records a diagnostic finding and updates JSON and Markdown reports on disk.
     *
     * @param finding   the diagnostic finding
     * @param outputDir directory to store report files
     */
    public synchronized void export(NPlusOneFinding finding, String outputDir) {
        if (finding == null) {
            return;
        }

        findings.add(finding);

        if (outputDir == null || outputDir.isBlank()) {
            outputDir = "target/nplus1-reports";
        }

        try {
            Path dirPath = Path.of(outputDir);
            Files.createDirectories(dirPath);

            writeJsonReport(dirPath);
            writeMarkdownSummary(dirPath);
        } catch (Exception e) {
            log.warn("Failed to export N+1 gate diagnostic reports to {}: {}", outputDir, e.getMessage());
        }
    }

    public List<NPlusOneFinding> getFindings() {
        return List.copyOf(findings);
    }

    public synchronized void reset() {
        findings.clear();
    }

    private void writeJsonReport(Path dirPath) throws IOException {
        Path jsonPath = dirPath.resolve("report.json");
        objectMapper.writeValue(jsonPath.toFile(), findings);
    }

    private void writeMarkdownSummary(Path dirPath) throws IOException {
        Path mdPath = dirPath.resolve("summary.md");
        StringBuilder sb = new StringBuilder();

        sb.append("# Hibernate N+1 AI Test Gate Summary\n\n");
        sb.append(String.format("Total findings detected: **%d**\n\n", findings.size()));

        sb.append("| Test Class | Test Method | Queries | Threshold | Mode | Suggested Fix |\n");
        sb.append("| :--- | :--- | :--- | :--- | :--- | :--- |\n");

        for (NPlusOneFinding f : findings) {
            String shortClass = extractSimpleName(f.testClass());
            String fixSummary = escapeMarkdown(truncate(f.recommendedFix(), 60));
            sb.append(String.format("| `%s` | `%s` | %d | %d | `%s` | %s |\n",
                    shortClass,
                    f.testMethod(),
                    f.repeatedQueryCount(),
                    f.threshold(),
                    f.mode(),
                    fixSummary
            ));
        }

        sb.append("\n---\n\n");

        int index = 1;
        for (NPlusOneFinding f : findings) {
            sb.append(String.format("### Finding %d: `%s.%s` (%s)\n\n",
                    index++,
                    extractSimpleName(f.testClass()),
                    f.testMethod(),
                    f.mode()
            ));
            sb.append(String.format("* **Repeated Queries**: %d (Threshold: %d)\n", f.repeatedQueryCount(), f.threshold()));
            sb.append(String.format("* **Parent Query (1)**: `%s`\n", f.parentQuery()));
            sb.append(String.format("* **Child Call Site (N)**: `%s`\n", f.childCallSite()));
            sb.append(String.format("* **Offending SQL**: `%s`\n", f.offendingSql()));
            sb.append(String.format("* **PostgreSQL EXPLAIN**: `%s`\n", f.explainPlan()));
            sb.append(String.format("* **AI Diagnosis**: %s\n", f.diagnosis()));
            sb.append(String.format("* **Recommended Fix**:\n\n```java\n%s\n```\n\n", f.recommendedFix()));
        }

        Files.writeString(mdPath, sb.toString(),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }

    private String extractSimpleName(String fullClassName) {
        if (fullClassName == null || fullClassName.isBlank()) {
            return "Unknown";
        }
        int dot = fullClassName.lastIndexOf('.');
        return dot >= 0 ? fullClassName.substring(dot + 1) : fullClassName;
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "N/A";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }

    private String escapeMarkdown(String text) {
        if (text == null) {
            return "N/A";
        }
        return text.replace("|", "\\|").replace("\n", " ");
    }
}

package com.builtbyjuls.gate.baseline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Service managing known N+1 baseline entries to allow ratcheting and legacy debt suppression.
 */
public class NPlusOneBaselineService {

    private static final Logger log = LoggerFactory.getLogger(NPlusOneBaselineService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final List<NPlusOneBaselineEntry> baselineEntries = Collections.synchronizedList(new ArrayList<>());
    private volatile String loadedFilePath = null;

    public NPlusOneBaselineService() {
    }

    /**
     * Loads baseline entries from the specified file path or classpath resource.
     *
     * @param filePath the path to nplus1-baseline.json
     */
    public synchronized void loadBaseline(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return;
        }

        // Avoid re-reading if already loaded for the same file path
        if (filePath.equals(loadedFilePath) && !baselineEntries.isEmpty()) {
            return;
        }

        baselineEntries.clear();
        loadedFilePath = filePath;

        try {
            Path path = Path.of(filePath);
            if (Files.exists(path)) {
                List<NPlusOneBaselineEntry> entries = objectMapper.readValue(
                        path.toFile(),
                        new TypeReference<List<NPlusOneBaselineEntry>>() {}
                );
                if (entries != null) {
                    baselineEntries.addAll(entries);
                    log.info("Loaded {} N+1 gate baseline entries from {}", baselineEntries.size(), path.toAbsolutePath());
                }
                return;
            }

            // Fall back to classpath lookup
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl != null) {
                try (InputStream is = cl.getResourceAsStream(filePath)) {
                    if (is != null) {
                        List<NPlusOneBaselineEntry> entries = objectMapper.readValue(
                                is,
                                new TypeReference<List<NPlusOneBaselineEntry>>() {}
                        );
                        if (entries != null) {
                            baselineEntries.addAll(entries);
                            log.info("Loaded {} N+1 gate baseline entries from classpath:{}", baselineEntries.size(), filePath);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load N+1 gate baseline from {}: {}", filePath, e.getMessage());
        }
    }

    /**
     * Checks if a violation matches any baseline entry.
     *
     * @param testClass        test class name
     * @param testMethod       test method name
     * @param childCallSite    child call site frame
     * @param queryFingerprint SHA-256 query fingerprint
     * @return true if the violation is covered by the baseline
     */
    public boolean isSuppressed(String testClass, String testMethod, String childCallSite, String queryFingerprint) {
        if (baselineEntries.isEmpty() || testClass == null || testMethod == null) {
            return false;
        }

        String targetSimpleClass = extractSimpleName(testClass);
        String targetCleanCallSite = sanitizeCallSite(childCallSite);

        synchronized (baselineEntries) {
            for (NPlusOneBaselineEntry entry : baselineEntries) {
                if (entry == null) {
                    continue;
                }

                boolean classMatch = testClass.equals(entry.testClass())
                        || targetSimpleClass.equals(extractSimpleName(entry.testClass()));
                boolean methodMatch = testMethod.equals(entry.testMethod());

                if (classMatch && methodMatch) {
                    if (queryFingerprint != null && queryFingerprint.equals(entry.queryFingerprint())) {
                        return true;
                    }
                    if (targetCleanCallSite != null && targetCleanCallSite.equals(sanitizeCallSite(entry.childCallSite()))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Records a new baseline entry and persists it to disk.
     *
     * @param entry            the baseline entry
     * @param baselineFilePath target file path to save
     */
    public synchronized void recordBaseline(NPlusOneBaselineEntry entry, String baselineFilePath) {
        if (entry == null) {
            return;
        }

        if (baselineFilePath == null || baselineFilePath.isBlank()) {
            baselineFilePath = "nplus1-baseline.json";
        }

        boolean alreadyExists = isSuppressed(
                entry.testClass(),
                entry.testMethod(),
                entry.childCallSite(),
                entry.queryFingerprint()
        );

        if (!alreadyExists) {
            baselineEntries.add(entry);
        }

        try {
            Path path = Path.of(baselineFilePath);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            objectMapper.writeValue(path.toFile(), baselineEntries);
            log.info("Persisted {} baseline entries to {}", baselineEntries.size(), path.toAbsolutePath());
        } catch (Exception e) {
            log.warn("Failed to persist baseline entries to {}: {}", baselineFilePath, e.getMessage());
        }
    }

    public List<NPlusOneBaselineEntry> getBaselineEntries() {
        return List.copyOf(baselineEntries);
    }

    public synchronized void reset() {
        baselineEntries.clear();
        loadedFilePath = null;
    }

    private String extractSimpleName(String fullClassName) {
        if (fullClassName == null) {
            return "";
        }
        int dot = fullClassName.lastIndexOf('.');
        return dot >= 0 ? fullClassName.substring(dot + 1) : fullClassName;
    }

    private String sanitizeCallSite(String callSite) {
        if (callSite == null) {
            return "";
        }
        // Strip line number e.g. (OrderService.java:25) -> (OrderService.java)
        return callSite.replaceAll(":\\d+\\)", ")");
    }
}

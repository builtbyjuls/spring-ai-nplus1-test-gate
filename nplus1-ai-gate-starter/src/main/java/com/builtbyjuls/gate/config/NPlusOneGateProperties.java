package com.builtbyjuls.gate.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the N+1 AI Test Gate.
 */
@ConfigurationProperties(prefix = "nplus1.gate")
public class NPlusOneGateProperties {

    /**
     * Whether the N+1 gate is globally enabled.
     * Default is true.
     */
    private boolean enabled = true;

    /**
     * Threshold of repeated SELECT queries before triggering N+1 detection.
     * Default is 5.
     */
    private int threshold = 5;

    /**
     * Base package to inspect for application stack frames and source lookup.
     */
    private String basePackage = "";

    /**
     * Enforcement mode for N+1 violations (FAIL or WARN).
     * Default is FAIL.
     */
    private GateMode mode = GateMode.FAIL;

    /**
     * Whether to export structured diagnostic reports (JSON and Markdown).
     * Default is true.
     */
    private boolean reportEnabled = true;

    /**
     * Directory where diagnostic reports are exported.
     * Default is "target/nplus1-reports".
     */
    private String reportOutputDir = "target/nplus1-reports";

    public GateMode getMode() {
        return mode;
    }

    public void setMode(GateMode mode) {
        this.mode = mode;
    }

    public boolean isReportEnabled() {
        return reportEnabled;
    }

    public void setReportEnabled(boolean reportEnabled) {
        this.reportEnabled = reportEnabled;
    }

    public String getReportOutputDir() {
        return reportOutputDir;
    }

    public void setReportOutputDir(String reportOutputDir) {
        this.reportOutputDir = reportOutputDir;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getThreshold() {
        return threshold;
    }

    public void setThreshold(int threshold) {
        this.threshold = threshold;
    }

    public String getBasePackage() {
        return basePackage;
    }

    public void setBasePackage(String basePackage) {
        this.basePackage = basePackage;
    }
}

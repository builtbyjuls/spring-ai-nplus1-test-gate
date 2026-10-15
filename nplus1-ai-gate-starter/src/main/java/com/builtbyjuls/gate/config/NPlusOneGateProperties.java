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

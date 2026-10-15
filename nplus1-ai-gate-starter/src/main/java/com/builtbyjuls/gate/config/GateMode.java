package com.builtbyjuls.gate.config;

/**
 * Enforcement mode for the N+1 AI Test Gate.
 */
public enum GateMode {

    /**
     * Fails the test with AssertionError when an N+1 query loop is detected.
     */
    FAIL,

    /**
     * Logs the full diagnostic report as a warning without failing the test.
     */
    WARN,

    /**
     * Defer to configuration property or global default (FAIL).
     */
    DEFAULT
}

package com.builtbyjuls.gate.annotation;

import com.builtbyjuls.gate.config.NPlusOneAiClientConfiguration;
import com.builtbyjuls.gate.config.NPlusOneGateAutoConfiguration;
import com.builtbyjuls.gate.listener.NPlusOneTestExecutionListener;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.test.context.TestExecutionListeners;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Enables the Hibernate N+1 AI Test Gate on test classes.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ImportAutoConfiguration({
        NPlusOneGateAutoConfiguration.class,
        NPlusOneAiClientConfiguration.class
})
@TestExecutionListeners(
        value = NPlusOneTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
public @interface EnableNPlusOneAiGate {

    /**
     * Whether the N+1 gate is enabled for this test class.
     * Default is true.
     */
    boolean enabled() default true;

    /**
     * Threshold for repeated queries before triggering N+1 failure.
     * Default is -1 (deferred to nplus1.gate.threshold property or default 5).
     */
    int threshold() default -1;

    /**
     * Base package to inspect for application stack frames and source resolution.
     */
    String basePackage() default "";
}

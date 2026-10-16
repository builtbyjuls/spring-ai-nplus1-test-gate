package com.builtbyjuls.gate.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Exempts a test class or test method from N+1 AI gate failures.
 * A mandatory reason must be provided documenting why the query loop is acceptable or when it will be refactored.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface IgnoreNPlusOne {

    /**
     * Mandatory explanation of why this N+1 loop is exempt from gate failures.
     */
    String reason();
}

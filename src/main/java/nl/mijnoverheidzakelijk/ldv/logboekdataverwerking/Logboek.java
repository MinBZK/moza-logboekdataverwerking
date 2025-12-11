package nl.mijnoverheidzakelijk.ldv.logboekdataverwerking;


import jakarta.enterprise.util.Nonbinding;
import jakarta.interceptor.InterceptorBinding;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Interceptor binding used to automatically create and enrich an OpenTelemetry span
 * around annotated methods or types. The bound interceptor adds LogboekDataverwerking-specific
 * attributes to the span.
 */
@InterceptorBinding
@Target({TYPE, METHOD})
@Retention(RUNTIME)
public @interface Logboek {

    /**
     * Optional name for the span that will be started by the interceptor.
     *
     * @return span name
     */
    @Nonbinding
    String name() default "";
    
    /**
     * Identifier of the processing activity to attach to the span as an attribute.
     *
     * @return processing activity id
     */
    @Nonbinding
    String processingActivityId() default "";

}

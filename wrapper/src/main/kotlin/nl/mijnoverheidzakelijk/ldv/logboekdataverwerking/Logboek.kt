package nl.mijnoverheidzakelijk.ldv.logboekdataverwerking

import jakarta.enterprise.util.Nonbinding
import jakarta.interceptor.InterceptorBinding


/**
 * Interceptor binding used to automatically create and enrich an OpenTelemetry span
 * around annotated methods or types. The bound interceptor adds LogboekDataverwerking-specific
 * attributes to the span.
 */
@InterceptorBinding
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER
)
@Retention(AnnotationRetention.RUNTIME)
annotation class Logboek(
    /**
     * Optional name for the span that will be started by the interceptor.
     * 
     * @return span name
     */
    @get:Nonbinding val name: String = "",
    /**
     * Identifier of the processing activity to attach to the span as an attribute.
     * 
     * @return processing activity id
     */
    @get:Nonbinding val processingActivityId: String = ""
)

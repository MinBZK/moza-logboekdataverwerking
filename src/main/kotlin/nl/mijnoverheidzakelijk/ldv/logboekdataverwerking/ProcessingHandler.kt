package nl.mijnoverheidzakelijk.ldv.logboekdataverwerking

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import jakarta.enterprise.context.ApplicationScoped
import nl.mijnoverheidzakelijk.ldv.config.ConfigurationLoader
import nl.mijnoverheidzakelijk.ldv.config.TelemetryConfig.initOpenTelemetry
import org.apache.commons.configuration2.ex.ConfigurationException

/**
 * Handles creation and enrichment of OpenTelemetry spans used by the Logboek
 * interceptor flow.
 */
@ApplicationScoped
class ProcessingHandler {
    private val tracer: Tracer

    /**
     * Initializes the handler by configuring OpenTelemetry and acquiring a tracer
     * for the configured service name.
     * 
     * @throws RuntimeException if configuration cannot be read or OpenTelemetry cannot be initialized
     */
    init {
        try {
            val serviceName =
                ConfigurationLoader.getValueByKey("logboekdataverwerking.service-name", String::class.java)

            val openTelemetry = initOpenTelemetry(serviceName)
            this.tracer = openTelemetry.getTracer(serviceName)
        } catch (e: ConfigurationException) {
            throw RuntimeException("Failed to initialize ProcessingHandler", e)
        }
    }

    /**
     * Starts a new span with the given name, optionally using an existing parent context.
     * 
     * @param name    the span name
     * @param context the parent context may be null
     * @return the started span
     */
    fun startSpan(name: String, context: Context): Span {
        return tracer.spanBuilder(name)
            .setParent(context)
            .startSpan()
    }

    /**
     * Adds Logboek context attributes and status to the given span.
     * 
     * @param span           the span to enrich
     * @param logboekContext the context holding attributes
     */
    fun addLogboekContextToSpan(span: Span, logboekContext: LogboekContext) {
        span.setAttribute("dpl.core.processing_activity_id", logboekContext.processingActivityId)
        span.setAttribute("dpl.core.data_subject_id", logboekContext.dataSubjectId)
        span.setAttribute("dpl.core.data_subject_id_type", logboekContext.dataSubjectType)
        span.setStatus(logboekContext.status)
    }
}
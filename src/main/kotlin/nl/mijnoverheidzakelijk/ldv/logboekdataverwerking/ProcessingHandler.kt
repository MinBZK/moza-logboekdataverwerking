package nl.mijnoverheidzakelijk.ldv.logboekdataverwerking

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import jakarta.enterprise.context.ApplicationScoped
import nl.mijnoverheidzakelijk.ldv.config.ConfigurationLoader
import nl.mijnoverheidzakelijk.ldv.exporter.ClickHouseSpanExporter
import nl.mijnoverheidzakelijk.ldv.exporter.DummySpanExporter
import org.apache.commons.configuration2.ex.ConfigurationException

/**
 * Handles creation and enrichment of OpenTelemetry spans used by the Logboek
 * interceptor flow.
 */
@ApplicationScoped
class ProcessingHandler {
    private val tracer: Tracer = openTelemetry.getTracer(serviceName)

    /**
     * Starts a new span with the given name, optionally using an existing parent context.
     * 
     * @param name    the span name
     * @param context the parent context may be null
     * @return the started span
     */
    fun startSpan(name: String, context: Context?): Span {
        if (context != null) {
            return tracer.spanBuilder(name)
                .setParent(context)
                .startSpan()
        }

        return tracer.spanBuilder(name)
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

    companion object {
        var openTelemetry: OpenTelemetry = initOpenTelemetry()
        val serviceName =
            ConfigurationLoader.getValueByKey("logboekdataverwerking.service-name", String::class.java)

        /**
         * Initializes and returns the global [OpenTelemetry] instance.
         * Subsequent calls return the already initialized instance.
         *
         * @param serviceName the service name to be set on spans as a resource attribute
         * @return the initialized [OpenTelemetry] instance
         * @throws ConfigurationException if exporter configuration cannot be read
         */
        @Synchronized
        @Throws(ConfigurationException::class)
        private fun initOpenTelemetry(): OpenTelemetry {
            val serviceName =
                ConfigurationLoader.getValueByKey("logboekdataverwerking.service-name", String::class.java)
            println("Initializing open telemetry service: $serviceName")

            val resource = Resource.getDefault().merge(
                Resource.create(
                    Attributes.of<String>(
                        AttributeKey.stringKey("service.name"), serviceName
                    )
                )
            )

            val exporter =
                if (ConfigurationLoader.getValueByKey("logboekdataverwerking.enabled", Boolean::class.java))
                    ClickHouseSpanExporter()
                else
                    DummySpanExporter()

            val tracerProvider = SdkTracerProvider.builder()
                .setResource(resource)
                .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
                .build()

            val openTelemetrySdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build()

            Runtime.getRuntime().addShutdownHook(Thread { openTelemetrySdk.close() })

            return openTelemetrySdk
        }
    }
}

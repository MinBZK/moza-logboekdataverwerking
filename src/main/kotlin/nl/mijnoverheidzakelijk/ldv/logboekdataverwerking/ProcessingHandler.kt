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
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import nl.mijnoverheidzakelijk.ldv.config.ConfigurationLoader
import nl.mijnoverheidzakelijk.ldv.exporter.ClickHouseSpanExporter
import nl.mijnoverheidzakelijk.ldv.exporter.DummySpanExporter
import org.apache.commons.configuration2.ex.ConfigurationException
import java.util.logging.Logger

/**
 * Handles creation and enrichment of OpenTelemetry spans used by the Logboek
 * interceptor flow.
 *
 * When running inside a CDI container that provides an [OpenTelemetry] bean
 * (e.g. Quarkus with its OpenTelemetry extension), that instance is used
 * directly. Otherwise, a standalone SDK instance is created and managed
 * by this handler.
 */
@ApplicationScoped
class ProcessingHandler {

    @Inject
    private lateinit var openTelemetryInstance: Instance<OpenTelemetry>

    private lateinit var openTelemetry: OpenTelemetry

    @PostConstruct
    fun init() {
        openTelemetry = if (openTelemetryInstance.isResolvable) {
            LOGGER.info("Using container-provided OpenTelemetry instance")
            openTelemetryInstance.get()
        } else {
            LOGGER.info("No container-provided OpenTelemetry found, creating standalone instance")
            initOpenTelemetry()
        }
    }

    /**
     * Starts a new span with the given name, optionally using an existing parent context.
     *
     * @param name    the span name
     * @param context the parent context may be null
     * @return the started span
     */
    fun startSpan(name: String, context: Context?): Span {
        val tracer: Tracer = openTelemetry.getTracer(serviceName)
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
        val processingActivityId = logboekContext.processingActivityId
        val dataSubjectId = logboekContext.dataSubjectId
        val dataSubjectType = logboekContext.dataSubjectType

        require(!processingActivityId.isNullOrEmpty()) { "dpl.core.processing_activity_id is required by the LDV standard" }
        require(!dataSubjectId.isNullOrEmpty()) { "dpl.core.data_subject_id is required by the LDV standard" }
        require(!dataSubjectType.isNullOrEmpty()) { "dpl.core.data_subject_id_type is required by the LDV standard" }

        try {
            val uri = java.net.URI(processingActivityId)
            require(uri.isAbsolute) { "dpl.core.processing_activity_id must be an absolute URI: $processingActivityId" }
        } catch (e: java.net.URISyntaxException) {
            throw IllegalArgumentException("dpl.core.processing_activity_id must be a valid URI: $processingActivityId", e)
        }

        span.setAttribute("dpl.core.processing_activity_id", processingActivityId)
        span.setAttribute("dpl.core.data_subject_id", dataSubjectId)
        span.setAttribute("dpl.core.data_subject_id_type", dataSubjectType)
        span.setStatus(logboekContext.status)
    }

    companion object {
        private val LOGGER: Logger = Logger.getLogger(ProcessingHandler::class.java.name)

        val serviceName: String by lazy { ConfigurationLoader.serviceName }

        /**
         * Creates a standalone [OpenTelemetry] instance for use outside a CDI container.
         *
         * @return the initialized [OpenTelemetry] instance
         * @throws ConfigurationException if exporter configuration cannot be read
         */
        @Throws(ConfigurationException::class)
        internal fun initOpenTelemetry(): OpenTelemetry {
            LOGGER.info("Initializing standalone OpenTelemetry for service: $serviceName")

            val resource = Resource.getDefault().merge(
                Resource.create(
                    Attributes.of(
                        AttributeKey.stringKey("service.name"), serviceName
                    )
                )
            )

            val exporter =
                if (ConfigurationLoader.enabled)
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

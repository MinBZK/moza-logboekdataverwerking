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
import io.opentelemetry.sdk.trace.SpanProcessor
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import nl.mijnoverheidzakelijk.ldv.config.ConfigurationLoader
import nl.mijnoverheidzakelijk.ldv.exporter.ClickHouseSpanExporter
import nl.mijnoverheidzakelijk.ldv.exporter.LdvSpanFilterProcessor
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
        // Use a dedicated, fixed instrumentation scope so LdvSpanFilterProcessor can
        // reliably route only LDV spans to ClickHouse on a host's shared OpenTelemetry SDK.
        val tracer: Tracer = openTelemetry.getTracer(LdvSpanFilterProcessor.LDV_INSTRUMENTATION_SCOPE)
        if (context != null) {
            return tracer.spanBuilder(name)
                .setParent(context)
                .startSpan()
        }

        return tracer.spanBuilder(name)
            .startSpan()
    }

    /**
     * Adds Logboek context attributes and (optionally) status to the given span.
     *
     * The [setStatus] parameter exists so callers can apply attributes without
     * overwriting a status that has already been set elsewhere. The interceptor
     * uses this to preserve an ERROR set on the exception path against a stale
     * OK that user code may have written to [LogboekContext] before throwing.
     *
     * @param span           the span to enrich
     * @param logboekContext the context holding attributes
     * @param setStatus      when true (default), applies [LogboekContext.status]
     *                       to the span via [Span.setStatus]
     */
    @JvmOverloads
    fun addLogboekContextToSpan(span: Span, logboekContext: LogboekContext, setStatus: Boolean = true) {
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
        if (setStatus) {
            span.setStatus(logboekContext.status)
        }
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

            val resource = Resource.getDefault().merge(Resource.create(buildResourceAttributes()))

            val tracerProvider = SdkTracerProvider.builder()
                .setResource(resource)
                .addSpanProcessor(buildLdvSpanProcessor())
                .build()

            val openTelemetrySdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build()

            Runtime.getRuntime().addShutdownHook(Thread { openTelemetrySdk.close() })

            return openTelemetrySdk
        }

        /**
         * Builds the LDV span-export pipeline. When LDV is enabled: the ClickHouse
         * exporter wrapped in the configured [SpanProcessor], then wrapped in
         * [LdvSpanFilterProcessor] so only LDV spans are exported. When disabled it
         * returns a no-op processor, so the package contributes nothing to a host's
         * SDK (no exporter, no worker thread, no per-span filtering).
         *
         * Shared by the standalone SDK path ([initOpenTelemetry]) and the CDI
         * producer ([LdvSpanProcessorProducer]) so that fail-loud config
         * validation and export behaviour are identical whether or not the host
         * app provides its own OpenTelemetry (e.g. quarkus-opentelemetry). This
         * method never creates an OpenTelemetry SDK, so it cannot introduce a
         * second instance.
         *
         * @throws IllegalStateException if `enabled` but ClickHouse config is incomplete
         */
        internal fun buildLdvSpanProcessor(): SpanProcessor {
            if (!ConfigurationLoader.enabled) {
                // Disabled: contribute nothing. This matters when the jar sits on a
                // host's classpath alongside an OTel integration that collects
                // SpanProcessor beans (e.g. quarkus-opentelemetry): with LDV off we
                // must not attach a processor (or its worker thread) to the host SDK.
                return SpanProcessor.composite(emptyList())
            }

            // Fail-loud on startup if the ClickHouse exporter is misconfigured,
            // instead of silently dropping spans at first export.
            ConfigurationLoader.validateClickhouseConfig()
            val exporter: SpanExporter = ClickHouseSpanExporter()

            val delegate: SpanProcessor = when (ConfigurationLoader.spanProcessor) {
                ConfigurationLoader.SpanProcessorMode.SIMPLE -> SimpleSpanProcessor.create(exporter)
                ConfigurationLoader.SpanProcessorMode.BATCH -> BatchSpanProcessor.builder(exporter).build()
            }

            return LdvSpanFilterProcessor(delegate)
        }

        private fun buildResourceAttributes(): Attributes {
            val builder = Attributes.builder()
            builder.put(AttributeKey.stringKey("service.name"), serviceName)
            ConfigurationLoader.serviceVersion?.let {
                builder.put(AttributeKey.stringKey("service.version"), it)
            }
            ConfigurationLoader.deploymentEnvironment?.let {
                builder.put(AttributeKey.stringKey("deployment.environment"), it)
            }
            return builder.build()
        }
    }
}

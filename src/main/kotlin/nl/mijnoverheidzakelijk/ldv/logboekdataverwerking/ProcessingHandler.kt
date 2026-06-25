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
import io.opentelemetry.sdk.trace.samplers.Sampler
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import nl.mijnoverheidzakelijk.ldv.config.ConfigurationLoader
import nl.mijnoverheidzakelijk.ldv.exporter.ClickHouseSpanExporter
import nl.mijnoverheidzakelijk.ldv.exporter.LdvSpanFilterProcessor
import nl.mijnoverheidzakelijk.ldv.exporter.LogboekWriteFailureRecorder
import org.apache.commons.configuration2.ex.ConfigurationException
import java.util.logging.Logger

/**
 * Creates and enriches the OpenTelemetry spans used by the Logboek interceptor flow.
 *
 * Uses a dedicated SDK rather than a host-provided one (e.g. quarkus-opentelemetry):
 * the host's sampler would otherwise be able to drop logregels, which the LDV spec
 * forbids (MUST NOT use Log Sampling). See [initOpenTelemetry].
 */
@ApplicationScoped
class ProcessingHandler {

    internal lateinit var openTelemetry: OpenTelemetry

    @PostConstruct
    fun init() {
        openTelemetry = initOpenTelemetry()
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
        require(!processingActivityId.isNullOrEmpty()) { "dpl.core.processing_activity_id is required by the LDV standard" }
        validateActivityUri(processingActivityId)

        // The LDV standard makes data_subject_id/_type optional (0..1); MOZa deliberately
        // requires at least one betrokkene (stricter than the spec, by design).
        val subjects = logboekContext.effectiveSubjects()
        require(subjects.isNotEmpty()) { "dpl.core.data_subject_id is required by the LDV standard" }
        subjects.forEach {
            require(it.id.isNotEmpty()) { "dpl.core.data_subject_id is required by the LDV standard" }
            require(it.type.isNotEmpty()) { "dpl.core.data_subject_id_type is required by the LDV standard" }
        }

        span.setAttribute("dpl.core.processing_activity_id", processingActivityId)
        if (setStatus) {
            span.setStatus(logboekContext.status)
        }

        if (subjects.size == 1) {
            applySubject(span, subjects[0])
        } else {
            // LDV requires a separate logregel per betrokkene; the action span stays
            // subject-less and each betrokkene becomes a child span.
            val parentContext = Context.root().with(span)
            val childName = logboekContext.actionName?.takeIf { it.isNotEmpty() } ?: CHILD_SPAN_NAME
            subjects.forEach { subject ->
                val child = startSpan(childName, parentContext)
                child.setAttribute("dpl.core.processing_activity_id", processingActivityId)
                applySubject(child, subject)
                if (setStatus) {
                    child.setStatus(logboekContext.status)
                }
                child.end()
            }
        }
    }

    private fun applySubject(span: Span, subject: DataSubject) {
        span.setAttribute("dpl.core.data_subject_id", subject.id)
        span.setAttribute("dpl.core.data_subject_id_type", subject.type)
    }

    private fun validateActivityUri(processingActivityId: String) {
        try {
            val uri = java.net.URI(processingActivityId)
            require(uri.isAbsolute) { "dpl.core.processing_activity_id must be an absolute URI: $processingActivityId" }
        } catch (e: java.net.URISyntaxException) {
            throw IllegalArgumentException("dpl.core.processing_activity_id must be a valid URI: $processingActivityId", e)
        }
    }

    /**
     * Always consumes the recorded write failure so none lingers on a pooled thread.
     * When [throwOnFailure] and policy is `FAIL_CLOSED`, rethrows it so a verwerking
     * does not count as logged when its logregel was not stored.
     */
    @JvmOverloads
    fun enforceWriteAcknowledgement(throwOnFailure: Boolean = true) {
        val failure = LogboekWriteFailureRecorder.consume() ?: return
        if (throwOnFailure && ConfigurationLoader.writeFailurePolicy == ConfigurationLoader.WriteFailurePolicy.FAIL_CLOSED) {
            throw LogboekWriteException("Logregel kon niet in het Logboek worden opgeslagen", failure)
        }
    }

    companion object {
        private val LOGGER: Logger = Logger.getLogger(ProcessingHandler::class.java.name)

        /** Name for per-betrokkene logregels when the action carries no human-readable name. */
        internal const val CHILD_SPAN_NAME: String = "verwerking-betrokkene"

        val serviceName: String by lazy { ConfigurationLoader.serviceName }

        /**
         * [Sampler.alwaysOn] so an inbound `traceparent` sampled-flag of `0` cannot drop
         * logregels (LDV MUST NOT sample). Not registered globally, so it coexists with a
         * host-provided OpenTelemetry.
         *
         * @throws ConfigurationException if exporter configuration cannot be read
         */
        @Throws(ConfigurationException::class)
        internal fun initOpenTelemetry(): OpenTelemetry {
            LOGGER.info("Initializing LDV OpenTelemetry for service: $serviceName")

            val resource = Resource.getDefault().merge(Resource.create(buildResourceAttributes()))

            val tracerProvider = SdkTracerProvider.builder()
                .setResource(resource)
                .setSampler(Sampler.alwaysOn())
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
         * returns a no-op processor, so the dedicated SDK does no work.
         *
         * @throws IllegalStateException if `enabled` but ClickHouse config is incomplete
         */
        internal fun buildLdvSpanProcessor(): SpanProcessor {
            if (!ConfigurationLoader.enabled) {
                // Disabled: contribute nothing (no exporter, no worker thread).
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

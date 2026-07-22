package nl.mijnoverheidzakelijk.ldv.logboekdataverwerking

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
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
import nl.mijnoverheidzakelijk.ldv.exporter.LdvSpanExporter
import nl.mijnoverheidzakelijk.ldv.exporter.LdvSpanFilterProcessor
import nl.mijnoverheidzakelijk.ldv.exporter.LogboekWriteFailureRecorder
import nl.mijnoverheidzakelijk.ldv.repository.ClickHouseRepository
import nl.mijnoverheidzakelijk.ldv.repository.PostgresRepository
import nl.mijnoverheidzakelijk.ldv.repository.SpanRepository
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
     * Adds Logboek context attributes and status to the given span.
     *
     * Validation never breaks the verwerking (LDV 3.3.2.1): a missing or invalid
     * [LogboekContext] field is logged as a warning (with `trace_id:span_id`) and
     * the logregel is exported with whatever attributes are present.
     *
     * The [propagatingFailure] parameter tells this method that an exception from the
     * intercepted method is already propagating. The span's own status is then left
     * untouched (the interceptor has already set ERROR on it) and per-betrokkene child
     * logregels get [StatusCode.ERROR] instead of [LogboekContext.status].
     *
     * @param span               the span to enrich
     * @param logboekContext     the context holding attributes
     * @param propagatingFailure when true, skips applying [LogboekContext.status] to
     *                           the span and marks child logregels as ERROR
     */
    @JvmOverloads
    fun addLogboekContextToSpan(
        span: Span,
        logboekContext: LogboekContext,
        propagatingFailure: Boolean = false
    ) {
        val processingActivityId = logboekContext.processingActivityId
        val subjects = logboekContext.effectiveSubjects()

        warnOnIncompleteContext(span, logboekContext, subjects)

        if (!processingActivityId.isNullOrEmpty()) {
            span.setAttribute("dpl.core.processing_activity_id", processingActivityId)
        }
        if (!propagatingFailure) {
            span.setStatus(logboekContext.status)
        }

        if (subjects.size > 1) {
            // LDV requires a separate logregel per betrokkene; the action span stays
            // subject-less and each betrokkene becomes a child span.
            val parentContext = Context.root().with(span)
            val childName = logboekContext.actionName?.takeIf { it.isNotEmpty() } ?: CHILD_SPAN_NAME
            val childStatus = if (propagatingFailure) StatusCode.ERROR else logboekContext.status
            subjects.forEach { subject ->
                val child = startSpan(childName, parentContext)
                if (!processingActivityId.isNullOrEmpty()) {
                    child.setAttribute("dpl.core.processing_activity_id", processingActivityId)
                }
                applySubject(child, subject)
                child.setStatus(childStatus)
                child.end()
            }
        } else if (subjects.size == 1) {
            applySubject(span, subjects[0])
        } else {
            // Half-set single pair: still apply what is present.
            logboekContext.dataSubjectId?.takeIf { it.isNotEmpty() }
                ?.let { span.setAttribute("dpl.core.data_subject_id", it) }
            logboekContext.dataSubjectType?.takeIf { it.isNotEmpty() }
                ?.let { span.setAttribute("dpl.core.data_subject_id_type", it) }
        }
    }

    /**
     * Warns (with the `trace_id:span_id`, so the incomplete logregel can be found
     * back in the Logboek) for every required LDV field that is missing or invalid.
     * Warns instead of throws: logging must never break the verwerking (LDV 3.3.2.1).
     * A logregel without betrokkene is valid for niet-persoonsgegevens verwerkingen;
     * the warning helps spot forgotten context.
     */
    private fun warnOnIncompleteContext(span: Span, logboekContext: LogboekContext, subjects: List<DataSubject>) {
        val processingActivityId = logboekContext.processingActivityId
        if (processingActivityId.isNullOrEmpty()) {
            warnIncomplete(span, "dpl.core.processing_activity_id is missing")
        } else if (!isAbsoluteUri(processingActivityId)) {
            warnIncomplete(span, "dpl.core.processing_activity_id is not a valid absolute URI: $processingActivityId")
        }
        if (subjects.isEmpty()) {
            if (!logboekContext.dataSubjectId.isNullOrEmpty()) {
                warnIncomplete(span, "dpl.core.data_subject_id_type is missing")
            } else if (!logboekContext.dataSubjectType.isNullOrEmpty()) {
                warnIncomplete(span, "dpl.core.data_subject_id is missing")
            } else {
                warnIncomplete(span, "no betrokkene (dpl.core.data_subject_id/_type) is set")
            }
        } else {
            subjects.forEach {
                if (it.id.isEmpty()) warnIncomplete(span, "dpl.core.data_subject_id is empty for a betrokkene")
                if (it.type.isEmpty()) warnIncomplete(span, "dpl.core.data_subject_id_type is empty for a betrokkene")
            }
        }
    }

    private fun warnIncomplete(span: Span, problem: String) {
        val sc = span.spanContext
        LOGGER.warning("$problem; the logregel is exported with incomplete context [${sc.traceId}:${sc.spanId}]")
    }

    private fun applySubject(span: Span, subject: DataSubject) {
        if (subject.id.isNotEmpty()) span.setAttribute("dpl.core.data_subject_id", subject.id)
        if (subject.type.isNotEmpty()) span.setAttribute("dpl.core.data_subject_id_type", subject.type)
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

    /**
     * @return true if [value] parses as an absolute URI per the LDV standard's
     *         requirement for `dpl.core.processing_activity_id`
     */
    private fun isAbsoluteUri(value: String): Boolean {
        return try {
            java.net.URI(value).isAbsolute
        } catch (e: java.net.URISyntaxException) {
            false
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
         * Builds the LDV span-export pipeline. When LDV is enabled: the database
         * exporter selected via `logboekdataverwerking.dbms` (ClickHouse or
         * PostgreSQL) wrapped in the configured [SpanProcessor], then wrapped in
         * [LdvSpanFilterProcessor] so only LDV spans are exported. When disabled it
         * returns a no-op processor, so the dedicated SDK does no work.
         *
         * @throws IllegalStateException if `enabled` but the selected backend's config is incomplete
         * @throws IllegalArgumentException if `logboekdataverwerking.dbms` is set to an unsupported value
         */
        internal fun buildLdvSpanProcessor(): SpanProcessor {
            if (!ConfigurationLoader.enabled) {
                // Disabled: contribute nothing (no exporter, no worker thread).
                return SpanProcessor.composite(emptyList())
            }

            // Fail-loud on startup if the selected backend is misconfigured,
            // instead of silently dropping spans at first export. The backend
            // choice is just which SpanRepository the shared LdvSpanExporter uses.
            val repository: SpanRepository = when (ConfigurationLoader.dbms) {
                ConfigurationLoader.Dbms.CLICKHOUSE -> {
                    ConfigurationLoader.validateClickhouseConfig()
                    ClickHouseRepository()
                }
                ConfigurationLoader.Dbms.POSTGRESQL -> {
                    ConfigurationLoader.validatePostgresqlConfig()
                    PostgresRepository()
                }
            }
            val mode = ConfigurationLoader.spanProcessor
            val exporter: SpanExporter = LdvSpanExporter(
                repository,
                relayWriteFailures = mode == ConfigurationLoader.SpanProcessorMode.SIMPLE,
            )

            val delegate: SpanProcessor = when (mode) {
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
